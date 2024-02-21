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

import static io.opentelemetry.semconv.SemanticAttributes.HTTP_METHOD;
import static io.opentelemetry.semconv.SemanticAttributes.HTTP_TARGET;
import static io.opentelemetry.semconv.SemanticAttributes.MESSAGING_OPERATION;
import static io.opentelemetry.semconv.SemanticAttributes.MessagingOperationValues.PROCESS;
import static io.opentelemetry.semconv.SemanticAttributes.RPC_SYSTEM;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_LOCAL_OPERATION;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

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
  // Max keyword length supported by parsing into remote_operation from DB_STATEMENT
  static final int MAX_KEYWORD_LENGTH = 27;
  private static final String SQL_DIALECT_KEYWORDS_JSON = "configuration/sql_dialect_keywords.json";

  static List<String> getDialectKeywords() throws IOException {
    InputStream jsonFile =
        AwsSpanProcessingUtil.class.getClassLoader().getResourceAsStream(SQL_DIALECT_KEYWORDS_JSON);
    ObjectMapper mapper = new ObjectMapper();
    JsonNode jsonNode = mapper.readValue(jsonFile, JsonNode.class);
    JsonNode arrayNode = jsonNode.get("keywords");
    ObjectReader reader = mapper.readerFor(new TypeReference<List<String>>() {});
    return reader.readValue(arrayNode);
  }

  /**
   * Ingress operation (i.e. operation for Server and Consumer spans) will be generated from
   * "http.method + http.target/with the first API path parameter" if the default span name equals
   * null, UnknownOperation or http.method value.
   */
  static String getIngressOperation(SpanData span) {
    String operation = span.getName();
    if (shouldUseInternalOperation(span)) {
      operation = INTERNAL_OPERATION;
    } else if (!isValidOperation(span, operation)) {
      operation = generateIngressOperation(span);
    }
    return operation;
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
    String messagingOperation = spanData.getAttributes().get(MESSAGING_OPERATION);
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
    String messagingOperation = spanData.getAttributes().get(MESSAGING_OPERATION);
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
    if (isKeyPresent(span, HTTP_METHOD)) {
      String httpMethod = span.getAttributes().get(HTTP_METHOD);
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
    if (isKeyPresent(span, HTTP_TARGET)) {
      String httpTarget = span.getAttributes().get(HTTP_TARGET);
      // get the first part from API path string as operation value
      // the more levels/parts we get from API path the higher chance for getting high cardinality
      // data
      if (httpTarget != null) {
        operation = extractAPIPathValue(httpTarget);
        if (isKeyPresent(span, HTTP_METHOD)) {
          String httpMethod = span.getAttributes().get(HTTP_METHOD);
          if (httpMethod != null) {
            operation = httpMethod + " " + operation;
          }
        }
      }
    }
    return operation;
  }
}
