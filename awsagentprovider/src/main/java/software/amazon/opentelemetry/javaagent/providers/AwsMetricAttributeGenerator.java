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

import static io.opentelemetry.semconv.ResourceAttributes.SERVICE_NAME;
import static io.opentelemetry.semconv.SemanticAttributes.DB_OPERATION;
import static io.opentelemetry.semconv.SemanticAttributes.DB_SYSTEM;
import static io.opentelemetry.semconv.SemanticAttributes.FAAS_INVOKED_NAME;
import static io.opentelemetry.semconv.SemanticAttributes.FAAS_TRIGGER;
import static io.opentelemetry.semconv.SemanticAttributes.GRAPHQL_OPERATION_TYPE;
import static io.opentelemetry.semconv.SemanticAttributes.HTTP_METHOD;
import static io.opentelemetry.semconv.SemanticAttributes.HTTP_STATUS_CODE;
import static io.opentelemetry.semconv.SemanticAttributes.HTTP_URL;
import static io.opentelemetry.semconv.SemanticAttributes.MESSAGING_OPERATION;
import static io.opentelemetry.semconv.SemanticAttributes.MESSAGING_SYSTEM;
import static io.opentelemetry.semconv.SemanticAttributes.NET_PEER_NAME;
import static io.opentelemetry.semconv.SemanticAttributes.NET_PEER_PORT;
import static io.opentelemetry.semconv.SemanticAttributes.NET_SOCK_PEER_ADDR;
import static io.opentelemetry.semconv.SemanticAttributes.NET_SOCK_PEER_PORT;
import static io.opentelemetry.semconv.SemanticAttributes.PEER_SERVICE;
import static io.opentelemetry.semconv.SemanticAttributes.RPC_METHOD;
import static io.opentelemetry.semconv.SemanticAttributes.RPC_SERVICE;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_BUCKET_NAME;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_LOCAL_OPERATION;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_LOCAL_SERVICE;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_QUEUE_NAME;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_REMOTE_OPERATION;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_REMOTE_SERVICE;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_REMOTE_TARGET;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_SPAN_KIND;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_STREAM_NAME;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_TABLE_NAME;
import static software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil.UNKNOWN_OPERATION;
import static software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil.UNKNOWN_REMOTE_OPERATION;
import static software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil.UNKNOWN_REMOTE_SERVICE;
import static software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil.UNKNOWN_SERVICE;
import static software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil.isKeyPresent;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.internal.data.ExceptionEventData;
import io.opentelemetry.semconv.ResourceAttributes;
import io.opentelemetry.semconv.SemanticAttributes;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * AwsMetricAttributeGenerator generates very specific metric attributes based on low-cardinality
 * span and resource attributes. If such attributes are not present, we fallback to default values.
 *
 * <p>The goal of these particular metric attributes is to get metrics for incoming and outgoing
 * traffic for a service. Namely, {@link SpanKind#SERVER} and {@link SpanKind#CONSUMER} spans
 * represent "incoming" traffic, {@link SpanKind#CLIENT} and {@link SpanKind#PRODUCER} spans
 * represent "outgoing" traffic, and {@link SpanKind#INTERNAL} spans are ignored.
 */
final class AwsMetricAttributeGenerator implements MetricAttributeGenerator {

  private static final Logger logger =
      Logger.getLogger(AwsMetricAttributeGenerator.class.getName());

  // Special DEPENDENCY attribute value if GRAPHQL_OPERATION_TYPE attribute key is present.
  private static final String GRAPHQL = "graphql";

  // As per
  // https://github.com/open-telemetry/opentelemetry-java/tree/main/sdk-extensions/autoconfigure#opentelemetry-resource
  // If service name is not specified, SDK defaults the service name to unknown_service:java
  private static final String OTEL_UNKNOWN_SERVICE = "unknown_service:java";

  // This method is used by the AwsSpanMetricsProcessor to generate service and dependency metrics
  @Override
  public Map<String, Attributes> generateMetricAttributeMapFromSpan(
      SpanData span, Resource resource) {
    Map<String, Attributes> attributesMap = new HashMap<>();
    if (AwsSpanProcessingUtil.shouldGenerateServiceMetricAttributes(span)) {
      attributesMap.put(
          MetricAttributeGenerator.SERVICE_METRIC, generateServiceMetricAttributes(span, resource));
    }
    if (AwsSpanProcessingUtil.shouldGenerateDependencyMetricAttributes(span)) {
      attributesMap.put(
          MetricAttributeGenerator.DEPENDENCY_METRIC,
          generateDependencyMetricAttributes(span, resource));
    }

    return attributesMap;
  }

  private Attributes generateServiceMetricAttributes(SpanData span, Resource resource) {
    AttributesBuilder builder = Attributes.builder();
    setService(resource, span, builder);
    setIngressOperation(span, builder);
    setSpanKindForService(span, builder);
    setHttpStatus(span, builder);

    return builder.build();
  }

  private Attributes generateDependencyMetricAttributes(SpanData span, Resource resource) {
    AttributesBuilder builder = Attributes.builder();
    setService(resource, span, builder);
    setEgressOperation(span, builder);
    setRemoteServiceAndOperation(span, builder);
    setRemoteTarget(span, builder);
    setSpanKindForDependency(span, builder);
    setHttpStatus(span, builder);

    return builder.build();
  }

  private static void setRemoteTarget(SpanData span, AttributesBuilder builder) {
    Optional<String> remoteTarget = getRemoteTarget(span);
    remoteTarget.ifPresent(s -> builder.put(AWS_REMOTE_TARGET, s));
  }

  /**
   * RemoteTarget attribute {@link AwsAttributeKeys#AWS_REMOTE_TARGET} is used to store the resource
   * name of the remote invokes, such as S3 bucket name, mysql table name, etc. TODO: currently only
   * support AWS resource name, will be extended to support the general remote targets, such as
   * ActiveMQ name, etc.
   */
  private static Optional<String> getRemoteTarget(SpanData span) {
    if (isKeyPresent(span, AWS_BUCKET_NAME)) {
      return Optional.ofNullable(span.getAttributes().get(AWS_BUCKET_NAME));
    } else if (isKeyPresent(span, AWS_QUEUE_NAME)) {
      return Optional.ofNullable(span.getAttributes().get(AWS_QUEUE_NAME));
    } else if (isKeyPresent(span, AWS_STREAM_NAME)) {
      return Optional.ofNullable(span.getAttributes().get(AWS_STREAM_NAME));
    } else if (isKeyPresent(span, AWS_TABLE_NAME)) {
      return Optional.ofNullable(span.getAttributes().get(AWS_TABLE_NAME));
    }
    return Optional.empty();
  }

  /** Service is always derived from {@link ResourceAttributes#SERVICE_NAME} */
  private static void setService(Resource resource, SpanData span, AttributesBuilder builder) {
    String service = resource.getAttribute(SERVICE_NAME);

    // In practice the service name is never null, but we can be defensive here.
    if (service == null || service.equals(OTEL_UNKNOWN_SERVICE)) {
      logUnknownAttribute(AWS_LOCAL_SERVICE, span);
      service = UNKNOWN_SERVICE;
    }
    builder.put(AWS_LOCAL_SERVICE, service);
  }

  /**
   * Ingress operation (i.e. operation for Server and Consumer spans) will be generated from
   * "http.method + http.target/with the first API path parameter" if the default span name equals
   * null, UnknownOperation or http.method value.
   */
  private static void setIngressOperation(SpanData span, AttributesBuilder builder) {
    String operation = AwsSpanProcessingUtil.getIngressOperation(span);
    if (operation.equals(UNKNOWN_OPERATION)) {
      logUnknownAttribute(AWS_LOCAL_OPERATION, span);
    }
    builder.put(AWS_LOCAL_OPERATION, operation);
  }

  /**
   * Egress operation (i.e. operation for Client and Producer spans) is always derived from a
   * special span attribute, {@link AwsAttributeKeys#AWS_LOCAL_OPERATION}. This attribute is
   * generated with a separate SpanProcessor, {@link AttributePropagatingSpanProcessor}
   */
  private static void setEgressOperation(SpanData span, AttributesBuilder builder) {
    String operation = AwsSpanProcessingUtil.getEgressOperation(span);
    if (operation == null) {
      logUnknownAttribute(AWS_LOCAL_OPERATION, span);
      operation = UNKNOWN_OPERATION;
    }
    builder.put(AWS_LOCAL_OPERATION, operation);
  }

  // add `AWS.SDK.` as prefix to indicate the metrics resulted from current span is from AWS SDK
  private static String normalizeServiceName(SpanData span, String serviceName) {
    if (AwsSpanProcessingUtil.isAwsSDKSpan(span)) {
      String scopeName = span.getInstrumentationScopeInfo().getName();
      if (scopeName.contains("aws-sdk-2.")) {
        return "AWS.SDK." + serviceName;
      }
    }
    return serviceName;
  }

  /**
   * Remote attributes (only for Client and Producer spans) are generated based on low-cardinality
   * span attributes, in priority order.
   *
   * <p>The first priority is the AWS Remote attributes, which are generated from manually
   * instrumented span attributes, and are clear indications of customer intent. If AWS Remote
   * attributes are not present, the next highest priority span attribute is Peer Service, which is
   * also a reliable indicator of customer intent. If this is set, it will override
   * AWS_REMOTE_SERVICE identified from any other span attribute, other than AWS Remote attributes.
   *
   * <p>After this, we look for the following low-cardinality span attributes that can be used to
   * determine the remote metric attributes:
   *
   * <ul>
   *   <li>RPC
   *   <li>DB
   *   <li>FAAS
   *   <li>Messaging
   *   <li>GraphQL - Special case, if {@link SemanticAttributes#GRAPHQL_OPERATION_TYPE} is present,
   *       we use it for RemoteOperation and set RemoteService to {@link #GRAPHQL}.
   * </ul>
   *
   * <p>In each case, these span attributes were selected from the OpenTelemetry trace semantic
   * convention specifications as they adhere to the three following criteria:
   *
   * <ul>
   *   <li>Attributes are meaningfully indicative of remote service/operation names.
   *   <li>Attributes are defined in the specification to be low cardinality, usually with a low-
   *       cardinality list of values.
   *   <li>Attributes are confirmed to have low-cardinality values, based on code analysis.
   * </ul>
   *
   * if the selected attributes are still producing the UnknownRemoteService or
   * UnknownRemoteOperation, `net.peer.name`, `net.peer.port`, `net.peer.sock.addr` and
   * `net.peer.sock.port` will be used to derive the RemoteService. And `http.method` and `http.url`
   * will be used to derive the RemoteOperation.
   */
  private static void setRemoteServiceAndOperation(SpanData span, AttributesBuilder builder) {
    String remoteService = UNKNOWN_REMOTE_SERVICE;
    String remoteOperation = UNKNOWN_REMOTE_OPERATION;
    if (isKeyPresent(span, AWS_REMOTE_SERVICE) || isKeyPresent(span, AWS_REMOTE_OPERATION)) {
      remoteService = getRemoteService(span, AWS_REMOTE_SERVICE);
      remoteOperation = getRemoteOperation(span, AWS_REMOTE_OPERATION);
    } else if (isKeyPresent(span, RPC_SERVICE) || isKeyPresent(span, RPC_METHOD)) {
      remoteService = normalizeServiceName(span, getRemoteService(span, RPC_SERVICE));
      remoteOperation = getRemoteOperation(span, RPC_METHOD);
    } else if (isKeyPresent(span, DB_SYSTEM) || isKeyPresent(span, DB_OPERATION)) {
      remoteService = getRemoteService(span, DB_SYSTEM);
      remoteOperation = getRemoteOperation(span, DB_OPERATION);
    } else if (isKeyPresent(span, FAAS_INVOKED_NAME) || isKeyPresent(span, FAAS_TRIGGER)) {
      remoteService = getRemoteService(span, FAAS_INVOKED_NAME);
      remoteOperation = getRemoteOperation(span, FAAS_TRIGGER);
    } else if (isKeyPresent(span, MESSAGING_SYSTEM) || isKeyPresent(span, MESSAGING_OPERATION)) {
      remoteService = getRemoteService(span, MESSAGING_SYSTEM);
      remoteOperation = getRemoteOperation(span, MESSAGING_OPERATION);
    } else if (isKeyPresent(span, GRAPHQL_OPERATION_TYPE)) {
      remoteService = GRAPHQL;
      remoteOperation = getRemoteOperation(span, GRAPHQL_OPERATION_TYPE);
    }

    // Peer service takes priority as RemoteService over everything but AWS Remote.
    if (isKeyPresent(span, PEER_SERVICE) && !isKeyPresent(span, AWS_REMOTE_SERVICE)) {
      remoteService = getRemoteService(span, PEER_SERVICE);
    }

    // try to derive RemoteService and RemoteOperation from the other related attributes
    if (remoteService.equals(UNKNOWN_REMOTE_SERVICE)) {
      remoteService = generateRemoteService(span);
    }
    if (remoteOperation.equals(UNKNOWN_REMOTE_OPERATION)) {
      remoteOperation = generateRemoteOperation(span);
    }

    builder.put(AWS_REMOTE_SERVICE, remoteService);
    builder.put(AWS_REMOTE_OPERATION, remoteOperation);
  }

  /**
   * When the remote call operation is undetermined for http use cases, will try to extract the
   * remote operation name from http url string
   */
  private static String generateRemoteOperation(SpanData span) {
    String remoteOperation = UNKNOWN_REMOTE_OPERATION;
    if (isKeyPresent(span, HTTP_URL)) {
      String httpUrl = span.getAttributes().get(HTTP_URL);
      try {
        URL url;
        if (httpUrl != null) {
          url = new URL(httpUrl);
          remoteOperation = AwsSpanProcessingUtil.extractAPIPathValue(url.getPath());
        }
      } catch (MalformedURLException e) {
        logger.log(Level.FINEST, "invalid http.url attribute: ", httpUrl);
      }
    }
    if (isKeyPresent(span, HTTP_METHOD)) {
      String httpMethod = span.getAttributes().get(HTTP_METHOD);
      remoteOperation = httpMethod + " " + remoteOperation;
    }
    if (remoteOperation.equals(UNKNOWN_REMOTE_OPERATION)) {
      logUnknownAttribute(AWS_REMOTE_OPERATION, span);
    }
    return remoteOperation;
  }

  private static String generateRemoteService(SpanData span) {
    String remoteService = UNKNOWN_REMOTE_SERVICE;
    if (isKeyPresent(span, NET_PEER_NAME)) {
      remoteService = getRemoteService(span, NET_PEER_NAME);
      if (isKeyPresent(span, NET_PEER_PORT)) {
        Long port = span.getAttributes().get(NET_PEER_PORT);
        remoteService += ":" + port;
      }
    } else if (isKeyPresent(span, NET_SOCK_PEER_ADDR)) {
      remoteService = getRemoteService(span, NET_SOCK_PEER_ADDR);
      if (isKeyPresent(span, NET_SOCK_PEER_PORT)) {
        Long port = span.getAttributes().get(NET_SOCK_PEER_PORT);
        remoteService += ":" + port;
      }
    } else {
      logUnknownAttribute(AWS_REMOTE_SERVICE, span);
    }
    return remoteService;
  }

  /** Span kind is needed for differentiating metrics in the EMF exporter */
  private static void setSpanKindForService(SpanData span, AttributesBuilder builder) {
    String spanKind = span.getKind().name();
    if (AwsSpanProcessingUtil.isLocalRoot(span)) {
      spanKind = AwsSpanProcessingUtil.LOCAL_ROOT;
    }
    builder.put(AWS_SPAN_KIND, spanKind);
  }

  private static void setSpanKindForDependency(SpanData span, AttributesBuilder builder) {
    String spanKind = span.getKind().name();
    builder.put(AWS_SPAN_KIND, spanKind);
  }

  /**
   * See comment on {@link #getAwsStatusCode}, this will set the http status code of the span and
   * allow for desired metric creation.
   */
  private static void setHttpStatus(SpanData span, AttributesBuilder builder) {
    if (isKeyPresent(span, HTTP_STATUS_CODE)) {
      return;
    }

    Long statusCode = getAwsStatusCode(span);
    if (statusCode != null) {
      builder.put(HTTP_STATUS_CODE, statusCode);
    }
  }

  /**
   * Attempt to pull status code from spans produced by AWS SDK instrumentation (both v1 and v2).
   * AWS SDK instrumentation does not populate http.status_code when non-200 status codes are
   * returned, as the AWS SDK throws exceptions rather than returning responses with status codes.
   * To work around this, we are attempting to get the exception out of the events, then calling
   * getStatusCode (for AWS SDK V1) and statusCode (for AWS SDK V2) to get the status code fromt the
   * exception. We rely on reflection here because we cannot cast the throwable to
   * AmazonServiceExceptions (V1) or AwsServiceExceptions (V2) because the throwable comes from a
   * separate class loader and attempts to cast will fail with ClassCastException.
   *
   * <p>TODO: Short term workaround. This can be completely removed once
   * https://github.com/open-telemetry/opentelemetry-java-contrib/issues/919 is resolved.
   */
  @Nullable
  private static Long getAwsStatusCode(SpanData spanData) {
    String scopeName = spanData.getInstrumentationScopeInfo().getName();
    if (!scopeName.contains("aws-sdk")) {
      return null;
    }

    for (EventData event : spanData.getEvents()) {
      if (event instanceof ExceptionEventData) {
        ExceptionEventData exceptionEvent = (ExceptionEventData) event;
        Throwable throwable = exceptionEvent.getException();

        try {
          Method method = throwable.getClass().getMethod("getStatusCode", new Class<?>[] {});
          Object code = method.invoke(throwable, new Object[] {});
          return Long.valueOf((Integer) code);
        } catch (Exception e) {
          // Take no action
        }

        try {
          Method method = throwable.getClass().getMethod("statusCode", new Class<?>[] {});
          Object code = method.invoke(throwable, new Object[] {});
          return Long.valueOf((Integer) code);
        } catch (Exception e) {
          // Take no action
        }
      }
    }

    return null;
  }

  private static String getRemoteService(SpanData span, AttributeKey<String> remoteServiceKey) {
    String remoteService = span.getAttributes().get(remoteServiceKey);
    if (remoteService == null) {
      remoteService = UNKNOWN_REMOTE_SERVICE;
    }
    return remoteService;
  }

  private static String getRemoteOperation(SpanData span, AttributeKey<String> remoteOperationKey) {
    String remoteOperation = span.getAttributes().get(remoteOperationKey);
    if (remoteOperation == null) {
      remoteOperation = UNKNOWN_REMOTE_OPERATION;
    }
    return remoteOperation;
  }

  private static void logUnknownAttribute(AttributeKey<String> attributeKey, SpanData span) {
    String[] params = {
      attributeKey.getKey(), span.getKind().name(), span.getSpanContext().getSpanId()
    };
    logger.log(Level.FINEST, "No valid {0} value found for {1} span {2}", params);
  }
}
