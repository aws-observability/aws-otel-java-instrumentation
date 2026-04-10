/*
 * Copyright Amazon.com, Inc. or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.opentelemetry.javaagent.providers;

import static io.opentelemetry.semconv.DbAttributes.DB_OPERATION_NAME;
import static io.opentelemetry.semconv.DbAttributes.DB_QUERY_TEXT;
import static io.opentelemetry.semconv.DbAttributes.DB_SYSTEM_NAME;
import static io.opentelemetry.semconv.UrlAttributes.URL_PATH;
import static io.opentelemetry.semconv.incubating.DbIncubatingAttributes.DB_OPERATION;
import static io.opentelemetry.semconv.incubating.DbIncubatingAttributes.DB_STATEMENT;
import static io.opentelemetry.semconv.incubating.DbIncubatingAttributes.DB_SYSTEM;
import static io.opentelemetry.semconv.incubating.HttpIncubatingAttributes.HTTP_METHOD;
import static io.opentelemetry.semconv.incubating.HttpIncubatingAttributes.HTTP_REQUEST_METHOD;
import static io.opentelemetry.semconv.incubating.HttpIncubatingAttributes.HTTP_TARGET;
import static io.opentelemetry.semconv.incubating.MessagingIncubatingAttributes.MESSAGING_OPERATION;
import static io.opentelemetry.semconv.incubating.MessagingIncubatingAttributes.MESSAGING_OPERATION_TYPE;
import static io.opentelemetry.semconv.incubating.MessagingIncubatingAttributes.MessagingOperationTypeIncubatingValues.PROCESS;
import static io.opentelemetry.semconv.incubating.RpcIncubatingAttributes.RPC_SYSTEM;
import static software.amazon.opentelemetry.javaagent.providers.AwsApplicationSignalsCustomizerProvider.AWS_LAMBDA_FUNCTION_NAME_CONFIG;
import static software.amazon.opentelemetry.javaagent.providers.AwsApplicationSignalsCustomizerProvider.isLambdaEnvironment;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_LAMBDA_LOCAL_OPERATION_OVERRIDE;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_LOCAL_OPERATION;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Utility class designed to support shared logic across AWS Span Processors. */
final class AwsSpanProcessingUtil {

  // Default attribute values if no valid span attribute value is identified
  static final String UNKNOWN_SERVICE = "UnknownService";
  static final String UNKNOWN_OPERATION = "UnknownOperation";
  static final String UNKNOWN_REMOTE_SERVICE = "UnknownRemoteService";
  static final String UNKNOWN_REMOTE_OPERATION = "UnknownRemoteOperation";
  static final String INTERNAL_OPERATION = "InternalOperation";
  static final String LOCAL_ROOT = "LOCAL_ROOT";
  static final String SQS_RECEIVE_MESSAGE_SPAN_NAME = "Sqs.ReceiveMessage";
  static final String AWS_SDK_INSTRUMENTATION_SCOPE_PREFIX = "io.opentelemetry.aws-sdk-";
  // Max keyword length supported by parsing into remote_operation from DB_STATEMENT.
  // The current longest command word is DATETIME_INTERVAL_PRECISION at 27 characters.
  // If we add a longer keyword to the sql dialect keyword list, need to update the constant below.
  static final int MAX_KEYWORD_LENGTH = 27;
  // TODO: Use Semantic Conventions once upgrade once upgrade to v1.26.0
  static final AttributeKey<String> GEN_AI_REQUEST_MODEL =
      AttributeKey.stringKey("gen_ai.request.model");
  static final Pattern SQL_DIALECT_PATTERN =
      Pattern.compile("^(?:" + String.join("|", getDialectKeywords()) + ")\\b");

  private static final String SQL_DIALECT_KEYWORDS_JSON = "configuration/sql_dialect_keywords.json";

  static final AttributeKey<String> OTEL_SCOPE_NAME = AttributeKey.stringKey("otel.scope.name");
  static final String LAMBDA_SCOPE_PREFIX = "io.opentelemetry.aws-lambda-";
  static final String SERVLET_SCOPE_PREFIX = "io.opentelemetry.servlet-";

  // Environment variable for configurable operation name paths
  static final String OTEL_AWS_HTTP_OPERATION_PATHS_CONFIG = "OTEL_AWS_HTTP_OPERATION_PATHS";

  // Parsed and sorted (longest first) operation paths from env var, computed once
  private static volatile List<String> operationPaths;

  /**
   * Parse the OTEL_AWS_HTTP_OPERATION_PATHS env var into a sorted list of path templates (longest
   * first). Returns an empty list if the env var is not set.
   */
  static List<String> getOperationPaths() {
    if (operationPaths == null) {
      synchronized (AwsSpanProcessingUtil.class) {
        if (operationPaths == null) {
          String config = System.getenv(OTEL_AWS_HTTP_OPERATION_PATHS_CONFIG);
          if (config == null || config.trim().isEmpty()) {
            operationPaths = Collections.emptyList();
          } else {
            List<String> paths = new ArrayList<>();
            for (String path : config.split(",")) {
              String trimmed = path.trim();
              if (!trimmed.isEmpty()) {
                paths.add(trimmed);
              }
            }
            // Sort longest first so longest prefix match wins. For patterns with the same
            // number of segments, the original configuration order is preserved (stable sort).
            paths.sort(
                (a, b) -> {
                  int aSegments = a.split("/").length;
                  int bSegments = b.split("/").length;
                  return Integer.compare(bSegments, aSegments);
                });
            operationPaths = Collections.unmodifiableList(paths);
          }
        }
      }
    }
    return operationPaths;
  }

  // Visible for testing — allows tests to reset the cached paths
  static void resetOperationPaths() {
    synchronized (AwsSpanProcessingUtil.class) {
      operationPaths = null;
    }
  }

  /**
   * If OTEL_AWS_HTTP_OPERATION_PATHS is configured and a pattern matches the span's URL path,
   * returns a wrapped SpanData with the span name overridden to "METHOD /path/template". Returns
   * the original span unchanged if no config is set or no pattern matches.
   */
  static SpanData applyOperationPathSpanName(SpanData span) {
    List<String> paths = getOperationPaths();
    if (paths.isEmpty()) {
      return span;
    }

    for (String urlPath : getUrlPathCandidates(span)) {
      if (urlPath == null || urlPath.isEmpty()) {
        continue;
      }

      // Strip query string and fragment (relevant for http.target)
      int idx = urlPath.indexOf('?');
      if (idx >= 0) {
        urlPath = urlPath.substring(0, idx);
      }
      idx = urlPath.indexOf('#');
      if (idx >= 0) {
        urlPath = urlPath.substring(0, idx);
      }

      // Normalize trailing slashes
      while (urlPath.endsWith("/") && urlPath.length() > 1) {
        urlPath = urlPath.substring(0, urlPath.length() - 1);
      }

      String[] urlSegments = urlPath.split("/", -1);
      for (String pattern : paths) {
        String normalizedPattern = pattern;
        while (normalizedPattern.endsWith("/") && normalizedPattern.length() > 1) {
          normalizedPattern = normalizedPattern.substring(0, normalizedPattern.length() - 1);
        }
        if (segmentsMatch(urlSegments, normalizedPattern.split("/", -1))) {
          String httpMethod = getHttpMethod(span);
          String newName = httpMethod != null ? httpMethod + " " + pattern : pattern;
          return new DelegatingSpanData(span) {
            @Override
            public String getName() {
              return newName;
            }
          };
        }
      }
    }
    return span;
  }

  /** Return URL path candidates from server span attributes: url.path, then http.target */
  private static String[] getUrlPathCandidates(SpanData span) {
    return new String[] {
      isKeyPresent(span, URL_PATH) ? span.getAttributes().get(URL_PATH) : null,
      isKeyPresent(span, HTTP_TARGET) ? span.getAttributes().get(HTTP_TARGET) : null,
    };
  }

  /**
   * Check if URL segments match a pattern's segments. Only pattern segments can be wildcards
   * ({param}, :param, or *) — URL segments are always treated as literals. A wildcard pattern
   * segment matches any non-empty URL segment. The pattern acts as a prefix — extra URL segments
   * after the pattern are allowed.
   */
  private static boolean segmentsMatch(String[] urlSegments, String[] patternSegments) {
    for (int i = 0; i < patternSegments.length; i++) {
      if (i >= urlSegments.length) {
        return false;
      }
      String ps = patternSegments[i];
      String us = urlSegments[i];

      // Pattern wildcard matches any non-empty URL segment
      if (isWildcardSegment(ps)) {
        if (us.isEmpty()) {
          return false;
        }
        continue;
      }

      // Both literal — must be equal
      if (!ps.equals(us)) {
        return false;
      }
    }
    return true;
  }

  /** A segment is a wildcard if it uses {param}, :param, or * format. */
  private static boolean isWildcardSegment(String segment) {
    return (segment.startsWith("{") && segment.endsWith("}"))
        || segment.startsWith(":")
        || segment.equals("*");
  }

  static List<String> getDialectKeywords() {
    try (InputStream jsonFile =
        AwsSpanProcessingUtil.class
            .getClassLoader()
            .getResourceAsStream(SQL_DIALECT_KEYWORDS_JSON)) {
      ObjectMapper mapper = new ObjectMapper();
      JsonNode jsonNode = mapper.readValue(jsonFile, JsonNode.class);
      JsonNode arrayNode = jsonNode.get("keywords");
      ObjectReader reader = mapper.readerFor(new TypeReference<List<String>>() {});
      return reader.readValue(arrayNode);
    } catch (IOException e) {
      return new ArrayList<>();
    }
  }

  /**
   * Ingress operation (i.e. operation for Server and Consumer spans) will be generated from
   * "http.method + http.target/with the first API path parameter" if the default span name equals
   * null, UnknownOperation or http.method value. If running in Lambda, the ingress operation will
   * be the function name + /FunctionHandler.
   */
  static String getIngressOperation(SpanData span) {
    if (isLambdaEnvironment()) {
      /*
       * By default the local operation of a Lambda span is hard-coded to "<FunctionName>/FunctionHandler".
       * To dynamically override this at runtime—such as when running a custom server inside your Lambda—
       * you can set the span attribute "aws.lambda.local.operation.override" before ending the span. For example:
       *
       *   // Obtain the current Span and override its operation name
       *   Span.current().setAttribute(
       *       "aws.lambda.local.operation.override",
       *       "MyServiceOperation");
       *
       * The code below will detect that override and use it instead of the default.
       */
      String operationOverride = span.getAttributes().get(AWS_LAMBDA_LOCAL_OPERATION_OVERRIDE);
      if (operationOverride != null) {
        return operationOverride;
      }
      String op = generateIngressOperation(span);
      if (!op.equals(UNKNOWN_OPERATION)) {
        return op;
      }
      return getFunctionNameFromEnv() + "/FunctionHandler";
    }

    String operation = span.getName();
    if (shouldUseInternalOperation(span)) {
      operation = INTERNAL_OPERATION;
    } else if (!isValidOperation(span, operation)) {
      operation = generateIngressOperation(span);
    }
    return operation;
  }

  /** Get the HTTP method from the span, checking new and deprecated semconv attributes. */
  private static String getHttpMethod(SpanData span) {
    if (isKeyPresent(span, HTTP_REQUEST_METHOD)) {
      return span.getAttributes().get(HTTP_REQUEST_METHOD);
    } else if (isKeyPresent(span, HTTP_METHOD)) {
      return span.getAttributes().get(HTTP_METHOD);
    }
    return null;
  }

  // define a function so that we can mock it in unit test
  static String getFunctionNameFromEnv() {
    return System.getenv(AWS_LAMBDA_FUNCTION_NAME_CONFIG);
  }

  static String getEgressOperation(SpanData span) {
    if (shouldUseInternalOperation(span)) {
      return INTERNAL_OPERATION;
    } else {
      return span.getAttributes().get(AWS_LOCAL_OPERATION);
    }
  }

  /**
   * Extract the first part from API http target if it exists
   *
   * @param httpTarget http request target string value. Eg, /payment/1234
   * @return the first part from the http target. Eg, /payment
   */
  static String extractAPIPathValue(String httpTarget) {
    if (httpTarget == null || httpTarget.isEmpty()) {
      return "/";
    }
    String[] paths = httpTarget.split("/");
    if (paths.length > 1) {
      return "/" + paths[1];
    }
    return "/";
  }

  static boolean isKeyPresent(SpanData span, AttributeKey<?> key) {
    return span.getAttributes().get(key) != null;
  }

  static <T> boolean isKeyPresentWithFallback(
      SpanData span, AttributeKey<T> key, AttributeKey<T> fallbackKey) {
    if (span.getAttributes().get(key) != null) {
      return true;
    }
    return isKeyPresent(span, fallbackKey);
  }

  static <T> T getKeyValueWithFallback(
      SpanData span, AttributeKey<T> key, AttributeKey<T> fallbackKey) {
    T value = span.getAttributes().get(key);
    if (value != null) {
      return value;
    }
    return span.getAttributes().get(fallbackKey);
  }

  static boolean isAwsSDKSpan(SpanData span) {
    // https://opentelemetry.io/docs/specs/otel/trace/semantic_conventions/instrumentation/aws-sdk/#common-attributes
    return "aws-api".equals(span.getAttributes().get(RPC_SYSTEM));
  }

  static boolean shouldGenerateServiceMetricAttributes(SpanData span) {
    return (isLocalRoot(span) && !isSqsReceiveMessageConsumerSpan(span))
        || SpanKind.SERVER.equals(span.getKind());
  }

  static boolean shouldGenerateDependencyMetricAttributes(SpanData span) {
    return SpanKind.CLIENT.equals(span.getKind())
        || SpanKind.PRODUCER.equals(span.getKind())
        || (isDependencyConsumerSpan(span) && !isSqsReceiveMessageConsumerSpan(span));
  }

  static boolean isConsumerProcessSpan(SpanData spanData) {
    String messagingOperation =
        getKeyValueWithFallback(spanData, MESSAGING_OPERATION_TYPE, MESSAGING_OPERATION);
    return SpanKind.CONSUMER.equals(spanData.getKind()) && PROCESS.equals(messagingOperation);
  }

  // Any spans that are Local Roots and also not SERVER should have aws.local.operation renamed to
  // InternalOperation.
  static boolean shouldUseInternalOperation(SpanData span) {
    return isLocalRoot(span) && !SpanKind.SERVER.equals(span.getKind());
  }

  // A span is a local root if it has no parent or if the parent is remote. This function checks the
  // parent context and returns true
  // if it is a local root.
  static boolean isLocalRoot(SpanData spanData) {
    SpanContext parentContext = spanData.getParentSpanContext();
    return parentContext == null || !parentContext.isValid() || parentContext.isRemote();
  }

  // To identify the SQS consumer spans produced by AWS SDK instrumentation
  private static boolean isSqsReceiveMessageConsumerSpan(SpanData spanData) {
    String spanName = spanData.getName();
    SpanKind spanKind = spanData.getKind();
    String messagingOperation =
        getKeyValueWithFallback(spanData, MESSAGING_OPERATION_TYPE, MESSAGING_OPERATION);
    InstrumentationScopeInfo instrumentationScopeInfo = spanData.getInstrumentationScopeInfo();

    return SQS_RECEIVE_MESSAGE_SPAN_NAME.equalsIgnoreCase(spanName)
        && SpanKind.CONSUMER.equals(spanKind)
        && instrumentationScopeInfo != null
        && instrumentationScopeInfo.getName().startsWith(AWS_SDK_INSTRUMENTATION_SCOPE_PREFIX)
        && (messagingOperation == null || messagingOperation.equals(PROCESS));
  }

  private static boolean isDependencyConsumerSpan(SpanData span) {
    if (!SpanKind.CONSUMER.equals(span.getKind())) {
      return false;
    } else if (isConsumerProcessSpan(span)) {
      if (isLocalRoot(span)) {
        return true;
      }
      String parentSpanKind =
          span.getAttributes().get(AwsAttributeKeys.AWS_CONSUMER_PARENT_SPAN_KIND);
      return !SpanKind.CONSUMER.name().equals(parentSpanKind);
    }
    return true;
  }

  /**
   * When Span name is null, UnknownOperation or HttpMethod value, it will be treated as invalid
   * local operation value that needs to be further processed
   */
  private static boolean isValidOperation(SpanData span, String operation) {
    if (operation == null || operation.equals(UNKNOWN_OPERATION)) {
      return false;
    }
    String httpMethod = getHttpMethod(span);
    if (httpMethod != null) {
      return !operation.equals(httpMethod);
    }
    return true;
  }

  /**
   * When span name is not meaningful(null, unknown or http_method value) as operation name for http
   * use cases. Will try to extract the operation name from http target string
   */
  private static String generateIngressOperation(SpanData span) {
    String operation = UNKNOWN_OPERATION;
    if (isKeyPresent(span, URL_PATH) || isKeyPresent(span, HTTP_TARGET)) {
      String httpTarget =
          isKeyPresent(span, URL_PATH)
              ? span.getAttributes().get(URL_PATH)
              : span.getAttributes().get(HTTP_TARGET);
      // get the first part from API path string as operation value
      // the more levels/parts we get from API path the higher chance for getting high cardinality
      // data
      if (httpTarget != null) {
        operation = extractAPIPathValue(httpTarget);
        if (isKeyPresent(span, HTTP_REQUEST_METHOD) || isKeyPresent(span, HTTP_METHOD)) {
          String httpMethod =
              isKeyPresent(span, HTTP_REQUEST_METHOD)
                  ? span.getAttributes().get(HTTP_REQUEST_METHOD)
                  : span.getAttributes().get(HTTP_METHOD);
          if (httpMethod != null) {
            operation = httpMethod + " " + operation;
          }
        }
      }
    }
    return operation;
  }

  // Check if the current Span adheres to database semantic conventions
  static boolean isDBSpan(SpanData span) {
    return isKeyPresentWithFallback(span, DB_SYSTEM_NAME, DB_SYSTEM)
        || isKeyPresentWithFallback(span, DB_OPERATION_NAME, DB_OPERATION)
        || isKeyPresentWithFallback(span, DB_QUERY_TEXT, DB_STATEMENT);
  }

  static boolean isLambdaServerSpan(ReadableSpan span) {
    String scopeName = null;
    if (span != null
        && span.toSpanData() != null
        && span.toSpanData().getInstrumentationScopeInfo() != null) {
      scopeName = span.toSpanData().getInstrumentationScopeInfo().getName();
    }

    return scopeName != null
        && scopeName.startsWith(LAMBDA_SCOPE_PREFIX)
        && SpanKind.SERVER == span.getKind();
  }

  static boolean isServletServerSpan(ReadableSpan span) {
    String scopeName = null;
    if (span != null
        && span.toSpanData() != null
        && span.toSpanData().getInstrumentationScopeInfo() != null) {
      scopeName = span.toSpanData().getInstrumentationScopeInfo().getName();
    }

    return scopeName != null
        && scopeName.startsWith(SERVLET_SCOPE_PREFIX)
        && SpanKind.SERVER == span.getKind();
  }
}
