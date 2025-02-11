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

import static io.opentelemetry.semconv.SemanticAttributes.DB_CONNECTION_STRING;
import static io.opentelemetry.semconv.SemanticAttributes.DB_NAME;
import static io.opentelemetry.semconv.SemanticAttributes.DB_OPERATION;
import static io.opentelemetry.semconv.SemanticAttributes.DB_STATEMENT;
import static io.opentelemetry.semconv.SemanticAttributes.DB_SYSTEM;
import static io.opentelemetry.semconv.SemanticAttributes.DB_USER;
import static io.opentelemetry.semconv.SemanticAttributes.FAAS_INVOKED_NAME;
import static io.opentelemetry.semconv.SemanticAttributes.FAAS_TRIGGER;
import static io.opentelemetry.semconv.SemanticAttributes.GRAPHQL_OPERATION_TYPE;
import static io.opentelemetry.semconv.SemanticAttributes.HTTP_METHOD;
import static io.opentelemetry.semconv.SemanticAttributes.HTTP_REQUEST_METHOD;
import static io.opentelemetry.semconv.SemanticAttributes.HTTP_RESPONSE_STATUS_CODE;
import static io.opentelemetry.semconv.SemanticAttributes.HTTP_STATUS_CODE;
import static io.opentelemetry.semconv.SemanticAttributes.HTTP_URL;
import static io.opentelemetry.semconv.SemanticAttributes.MESSAGING_OPERATION;
import static io.opentelemetry.semconv.SemanticAttributes.MESSAGING_SYSTEM;
import static io.opentelemetry.semconv.SemanticAttributes.NETWORK_PEER_ADDRESS;
import static io.opentelemetry.semconv.SemanticAttributes.NETWORK_PEER_PORT;
import static io.opentelemetry.semconv.SemanticAttributes.NET_PEER_NAME;
import static io.opentelemetry.semconv.SemanticAttributes.NET_PEER_PORT;
import static io.opentelemetry.semconv.SemanticAttributes.NET_SOCK_PEER_ADDR;
import static io.opentelemetry.semconv.SemanticAttributes.NET_SOCK_PEER_PORT;
import static io.opentelemetry.semconv.SemanticAttributes.PEER_SERVICE;
import static io.opentelemetry.semconv.SemanticAttributes.RPC_METHOD;
import static io.opentelemetry.semconv.SemanticAttributes.RPC_SERVICE;
import static io.opentelemetry.semconv.SemanticAttributes.SERVER_ADDRESS;
import static io.opentelemetry.semconv.SemanticAttributes.SERVER_PORT;
import static io.opentelemetry.semconv.SemanticAttributes.SERVER_SOCKET_ADDRESS;
import static io.opentelemetry.semconv.SemanticAttributes.SERVER_SOCKET_PORT;
import static io.opentelemetry.semconv.SemanticAttributes.URL_FULL;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_AGENT_ID;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_BUCKET_NAME;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_CLOUDFORMATION_PRIMARY_IDENTIFIER;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_DATA_SOURCE_ID;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_GUARDRAIL_ARN;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_GUARDRAIL_ID;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_KNOWLEDGE_BASE_ID;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_LAMBDA_ARN;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_LAMBDA_NAME;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_LAMBDA_RESOURCE_ID;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_LOCAL_OPERATION;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_LOCAL_SERVICE;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_QUEUE_NAME;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_QUEUE_URL;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_REMOTE_DB_USER;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_REMOTE_ENVIRONMENT;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_REMOTE_OPERATION;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_REMOTE_RESOURCE_IDENTIFIER;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_REMOTE_RESOURCE_TYPE;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_REMOTE_SERVICE;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_SECRET_ARN;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_SNS_TOPIC_ARN;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_SPAN_KIND;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_STATE_MACHINE_ARN;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_STEP_FUNCTIONS_ACTIVITY_ARN;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_STREAM_NAME;
import static software.amazon.opentelemetry.javaagent.providers.AwsAttributeKeys.AWS_TABLE_NAME;
import static software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil.GEN_AI_REQUEST_MODEL;
import static software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil.MAX_KEYWORD_LENGTH;
import static software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil.SQL_DIALECT_PATTERN;
import static software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil.UNKNOWN_OPERATION;
import static software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil.UNKNOWN_REMOTE_OPERATION;
import static software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil.UNKNOWN_REMOTE_SERVICE;
import static software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil.isAwsSDKSpan;
import static software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil.isDBSpan;
import static software.amazon.opentelemetry.javaagent.providers.AwsSpanProcessingUtil.isKeyPresent;

import com.amazonaws.arn.Arn;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.ExceptionEventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.semconv.ResourceAttributes;
import io.opentelemetry.semconv.SemanticAttributes;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
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

  // Normalized remote service names for supported AWS services
  private static final String NORMALIZED_DYNAMO_DB_SERVICE_NAME = "AWS::DynamoDB";
  private static final String NORMALIZED_KINESIS_SERVICE_NAME = "AWS::Kinesis";
  private static final String NORMALIZED_S3_SERVICE_NAME = "AWS::S3";
  private static final String NORMALIZED_SQS_SERVICE_NAME = "AWS::SQS";
  private static final String NORMALIZED_BEDROCK_SERVICE_NAME = "AWS::Bedrock";
  private static final String NORMALIZED_BEDROCK_RUNTIME_SERVICE_NAME = "AWS::BedrockRuntime";
  private static final String NORMALIZED_STEPFUNCTIONS_SERVICE_NAME = "AWS::StepFunctions";
  private static final String NORMALIZED_SNS_SERVICE_NAME = "AWS::SNS";
  private static final String NORMALIZED_SECRETSMANAGER_SERVICE_NAME = "AWS::SecretsManager";
  private static final String NORMALIZED_LAMBDA_SERVICE_NAME = "AWS::Lambda";

  // Special DEPENDENCY attribute value if GRAPHQL_OPERATION_TYPE attribute key is present.
  private static final String GRAPHQL = "graphql";

  private static final String DB_CONNECTION_RESOURCE_TYPE = "DB::Connection";

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
    setRemoteResourceTypeAndIdentifier(span, builder);
    setSpanKindForDependency(span, builder);
    setHttpStatus(span, builder);
    setRemoteDbUser(span, builder);

    return builder.build();
  }

  /** Service is always derived from {@link ResourceAttributes#SERVICE_NAME} */
  private static void setService(Resource resource, SpanData span, AttributesBuilder builder) {
    AwsResourceAttributeConfigurator.setServiceAttribute(
        resource, builder, () -> logUnknownAttribute(AWS_LOCAL_SERVICE, span));
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
   * UnknownRemoteOperation, `net.peer.name`, `net.peer.port`, `net.peer.sock.addr`,
   * `net.peer.sock.port` and `http.url` will be used to derive the RemoteService. And `http.method`
   * and `http.url` will be used to derive the RemoteOperation.
   */
  private static void setRemoteServiceAndOperation(SpanData span, AttributesBuilder builder) {
    String remoteService = UNKNOWN_REMOTE_SERVICE;
    String remoteOperation = UNKNOWN_REMOTE_OPERATION;

    if (isKeyPresent(span, AWS_REMOTE_SERVICE) || isKeyPresent(span, AWS_REMOTE_OPERATION)) {
      remoteService = getRemoteService(span, AWS_REMOTE_SERVICE);
      remoteOperation = getRemoteOperation(span, AWS_REMOTE_OPERATION);
    } else if (isKeyPresent(span, RPC_SERVICE) || isKeyPresent(span, RPC_METHOD)) {
      remoteService = normalizeRemoteServiceName(span, getRemoteService(span, RPC_SERVICE));
      remoteOperation = getRemoteOperation(span, RPC_METHOD);

    } else if (isDBSpan(span)) {
      remoteService = getRemoteService(span, DB_SYSTEM);
      if (isKeyPresent(span, DB_OPERATION)) {
        remoteOperation = getRemoteOperation(span, DB_OPERATION);
      } else {
        remoteOperation = getDBStatementRemoteOperation(span, DB_STATEMENT);
      }
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
    if (isKeyPresent(span, URL_FULL) || isKeyPresent(span, HTTP_URL)) {
      String httpUrl =
          isKeyPresent(span, URL_FULL)
              ? span.getAttributes().get(URL_FULL)
              : span.getAttributes().get(HTTP_URL);
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
    if (isKeyPresent(span, HTTP_REQUEST_METHOD) || isKeyPresent(span, HTTP_METHOD)) {
      String httpMethod =
          isKeyPresent(span, HTTP_REQUEST_METHOD)
              ? span.getAttributes().get(HTTP_REQUEST_METHOD)
              : span.getAttributes().get(HTTP_METHOD);
      remoteOperation = httpMethod + " " + remoteOperation;
    }
    if (remoteOperation.equals(UNKNOWN_REMOTE_OPERATION)) {
      logUnknownAttribute(AWS_REMOTE_OPERATION, span);
    }
    return remoteOperation;
  }

  private static String generateRemoteService(SpanData span) {
    String remoteService = UNKNOWN_REMOTE_SERVICE;
    if (isKeyPresent(span, SERVER_ADDRESS)) {
      remoteService = getRemoteService(span, SERVER_ADDRESS);
      if (isKeyPresent(span, SERVER_PORT)) {
        Long port = span.getAttributes().get(SERVER_PORT);
        remoteService += ":" + port;
      }
    } else if (isKeyPresent(span, NET_PEER_NAME)) {
      remoteService = getRemoteService(span, NET_PEER_NAME);
      if (isKeyPresent(span, NET_PEER_PORT)) {
        Long port = span.getAttributes().get(NET_PEER_PORT);
        remoteService += ":" + port;
      }
    } else if (isKeyPresent(span, NETWORK_PEER_ADDRESS)) {
      remoteService = getRemoteService(span, NETWORK_PEER_ADDRESS);
      if (isKeyPresent(span, NETWORK_PEER_PORT)) {
        Long port = span.getAttributes().get(NETWORK_PEER_PORT);
        remoteService += ":" + port;
      }
    } else if (isKeyPresent(span, NET_SOCK_PEER_ADDR)) {
      remoteService = getRemoteService(span, NET_SOCK_PEER_ADDR);
      if (isKeyPresent(span, NET_SOCK_PEER_PORT)) {
        Long port = span.getAttributes().get(NET_SOCK_PEER_PORT);
        remoteService += ":" + port;
      }
    } else if (isKeyPresent(span, URL_FULL) || isKeyPresent(span, HTTP_URL)) {
      String httpUrl =
          isKeyPresent(span, URL_FULL)
              ? span.getAttributes().get(URL_FULL)
              : span.getAttributes().get(HTTP_URL);
      try {
        URL url = new URL(httpUrl);
        if (!url.getHost().isEmpty()) {
          remoteService = url.getHost();
          if (url.getPort() != -1) {
            remoteService += ":" + url.getPort();
          }
        }
      } catch (MalformedURLException e) {
        logger.log(Level.FINEST, "invalid http.url attribute: ", httpUrl);
      }
    } else {
      logUnknownAttribute(AWS_REMOTE_SERVICE, span);
    }
    return remoteService;
  }

  /**
   * If the span is an AWS SDK span, normalize the name to align with <a
   * href="https://docs.aws.amazon.com/cloudcontrolapi/latest/userguide/supported-resources.html">AWS
   * Cloud Control resource format</a> as much as possible, with special attention to services we
   * can detect remote resource information for. Long term, we would like to normalize service name
   * in the upstream.
   */
  private static String normalizeRemoteServiceName(SpanData span, String serviceName) {
    if (AwsSpanProcessingUtil.isAwsSDKSpan(span)) {
      switch (serviceName) {
        case "AmazonDynamoDBv2": // AWS SDK v1
        case "DynamoDb": // AWS SDK v2
          return NORMALIZED_DYNAMO_DB_SERVICE_NAME;
        case "AmazonKinesis": // AWS SDK v1
        case "Kinesis": // AWS SDK v2
          return NORMALIZED_KINESIS_SERVICE_NAME;
        case "Amazon S3": // AWS SDK v1
        case "S3": // AWS SDK v2
          return NORMALIZED_S3_SERVICE_NAME;
        case "AmazonSQS": // AWS SDK v1
        case "Sqs": // AWS SDK v2
          return NORMALIZED_SQS_SERVICE_NAME;
          // For Bedrock, Bedrock Agent, and Bedrock Agent Runtime, we can align with AWS Cloud
          // Control and use AWS::Bedrock for RemoteService.
        case "AmazonBedrock": // AWS SDK v1
        case "Bedrock": // AWS SDK v2
        case "AWSBedrockAgentRuntime": // AWS SDK v1
        case "BedrockAgentRuntime": // AWS SDK v2
        case "AWSBedrockAgent": // AWS SDK v1
        case "BedrockAgent": // AWS SDK v2
          return NORMALIZED_BEDROCK_SERVICE_NAME;
          // For BedrockRuntime, we are using AWS::BedrockRuntime as the associated remote resource
          // (Model) is not listed in Cloud Control.
        case "AmazonBedrockRuntime": // AWS SDK v1
        case "BedrockRuntime": // AWS SDK v2
          return NORMALIZED_BEDROCK_RUNTIME_SERVICE_NAME;
        case "AWSStepFunctions": // AWS SDK v1
        case "Sfn": // AWS SDK v2
          return NORMALIZED_STEPFUNCTIONS_SERVICE_NAME;
        case "AmazonSNS":
        case "Sns":
          return NORMALIZED_SNS_SERVICE_NAME;
        case "AWSSecretsManager": // AWS SDK v1
        case "SecretsManager": // AWS SDK v2
          return NORMALIZED_SECRETSMANAGER_SERVICE_NAME;
        case "AWSLambda": // AWS SDK v1
        case "Lambda": // AWS SDK v2
          return NORMALIZED_LAMBDA_SERVICE_NAME;
        default:
          return "AWS::" + serviceName;
      }
    }
    return serviceName;
  }

  /**
   * Remote resource attributes {@link AwsAttributeKeys#AWS_REMOTE_RESOURCE_TYPE} and {@link
   * AwsAttributeKeys#AWS_REMOTE_RESOURCE_IDENTIFIER} are used to store information about the
   * resource associated with the remote invocation, such as S3 bucket name, etc. We should only
   * ever set both type and identifier or neither. If any identifier value contains | or ^ , they
   * will be replaced with ^| or ^^.
   *
   * <p>AWS resources type and identifier adhere to <a
   * href="https://docs.aws.amazon.com/cloudcontrolapi/latest/userguide/supported-resources.html">AWS
   * Cloud Control resource format</a>.
   */
  private static void setRemoteResourceTypeAndIdentifier(SpanData span, AttributesBuilder builder) {
    Optional<String> remoteResourceType = Optional.empty();
    Optional<String> remoteResourceIdentifier = Optional.empty();
    Optional<String> cloudformationPrimaryIdentifier = Optional.empty();

    if (isAwsSDKSpan(span)) {
      if (isKeyPresent(span, AWS_TABLE_NAME)) {
        remoteResourceType = Optional.of(NORMALIZED_DYNAMO_DB_SERVICE_NAME + "::Table");
        remoteResourceIdentifier =
            Optional.ofNullable(escapeDelimiters(span.getAttributes().get(AWS_TABLE_NAME)));
      } else if (isKeyPresent(span, AWS_STREAM_NAME)) {
        remoteResourceType = Optional.of(NORMALIZED_KINESIS_SERVICE_NAME + "::Stream");
        remoteResourceIdentifier =
            Optional.ofNullable(escapeDelimiters(span.getAttributes().get(AWS_STREAM_NAME)));
      } else if (isKeyPresent(span, AWS_BUCKET_NAME)) {
        remoteResourceType = Optional.of(NORMALIZED_S3_SERVICE_NAME + "::Bucket");
        remoteResourceIdentifier =
            Optional.ofNullable(escapeDelimiters(span.getAttributes().get(AWS_BUCKET_NAME)));
      } else if (isKeyPresent(span, AWS_QUEUE_NAME)) {
        remoteResourceType = Optional.of(NORMALIZED_SQS_SERVICE_NAME + "::Queue");
        remoteResourceIdentifier =
            Optional.ofNullable(escapeDelimiters(span.getAttributes().get(AWS_QUEUE_NAME)));
      } else if (isKeyPresent(span, AWS_QUEUE_URL)) {
        remoteResourceType = Optional.of(NORMALIZED_SQS_SERVICE_NAME + "::Queue");
        remoteResourceIdentifier =
            SqsUrlParser.getQueueName(escapeDelimiters(span.getAttributes().get(AWS_QUEUE_URL)));
      } else if (isKeyPresent(span, AWS_AGENT_ID)) {
        remoteResourceType = Optional.of(NORMALIZED_BEDROCK_SERVICE_NAME + "::Agent");
        remoteResourceIdentifier =
            Optional.ofNullable(escapeDelimiters(span.getAttributes().get(AWS_AGENT_ID)));
      } else if (isKeyPresent(span, AWS_KNOWLEDGE_BASE_ID)) {
        remoteResourceType = Optional.of(NORMALIZED_BEDROCK_SERVICE_NAME + "::KnowledgeBase");
        remoteResourceIdentifier =
            Optional.ofNullable(escapeDelimiters(span.getAttributes().get(AWS_KNOWLEDGE_BASE_ID)));
      } else if (isKeyPresent(span, AWS_DATA_SOURCE_ID)) {
        remoteResourceType = Optional.of(NORMALIZED_BEDROCK_SERVICE_NAME + "::DataSource");
        remoteResourceIdentifier =
            Optional.ofNullable(escapeDelimiters(span.getAttributes().get(AWS_DATA_SOURCE_ID)));
      } else if (isKeyPresent(span, AWS_GUARDRAIL_ID)) {
        remoteResourceType = Optional.of(NORMALIZED_BEDROCK_SERVICE_NAME + "::Guardrail");
        remoteResourceIdentifier =
            Optional.ofNullable(escapeDelimiters(span.getAttributes().get(AWS_GUARDRAIL_ID)));
        cloudformationPrimaryIdentifier =
            Optional.ofNullable(escapeDelimiters(span.getAttributes().get(AWS_GUARDRAIL_ARN)));
      } else if (isKeyPresent(span, GEN_AI_REQUEST_MODEL)) {
        remoteResourceType = Optional.of(NORMALIZED_BEDROCK_SERVICE_NAME + "::Model");
        remoteResourceIdentifier =
            Optional.ofNullable(escapeDelimiters(span.getAttributes().get(GEN_AI_REQUEST_MODEL)));
      } else if (isKeyPresent(span, AWS_STATE_MACHINE_ARN)) {
        remoteResourceType = Optional.of(NORMALIZED_STEPFUNCTIONS_SERVICE_NAME + "::StateMachine");
        remoteResourceIdentifier =
            getSfnResourceNameFromArn(
                Optional.ofNullable(
                    escapeDelimiters(span.getAttributes().get(AWS_STATE_MACHINE_ARN))));
        cloudformationPrimaryIdentifier =
            Optional.ofNullable(escapeDelimiters(span.getAttributes().get(AWS_STATE_MACHINE_ARN)));
      } else if (isKeyPresent(span, AWS_STEP_FUNCTIONS_ACTIVITY_ARN)) {
        remoteResourceType = Optional.of(NORMALIZED_STEPFUNCTIONS_SERVICE_NAME + "::Activity");
        remoteResourceIdentifier =
            getSfnResourceNameFromArn(
                Optional.ofNullable(
                    escapeDelimiters(span.getAttributes().get(AWS_STEP_FUNCTIONS_ACTIVITY_ARN))));
        cloudformationPrimaryIdentifier =
            Optional.ofNullable(
                escapeDelimiters(span.getAttributes().get(AWS_STEP_FUNCTIONS_ACTIVITY_ARN)));
      } else if (isKeyPresent(span, AWS_SNS_TOPIC_ARN)) {
        remoteResourceType = Optional.of(NORMALIZED_SNS_SERVICE_NAME + "::Topic");
        remoteResourceIdentifier =
            getSnsResourceNameFromArn(
                Optional.ofNullable(escapeDelimiters(span.getAttributes().get(AWS_SNS_TOPIC_ARN))));
        cloudformationPrimaryIdentifier =
            Optional.ofNullable(escapeDelimiters(span.getAttributes().get(AWS_SNS_TOPIC_ARN)));
      } else if (isKeyPresent(span, AWS_SECRET_ARN)) {
        remoteResourceType = Optional.of(NORMALIZED_SECRETSMANAGER_SERVICE_NAME + "::Secret");
        remoteResourceIdentifier =
            getSecretsManagerResourceNameFromArn(
                Optional.ofNullable(escapeDelimiters(span.getAttributes().get(AWS_SECRET_ARN))));
        cloudformationPrimaryIdentifier =
            Optional.ofNullable(escapeDelimiters(span.getAttributes().get(AWS_SECRET_ARN)));
      } else if (isKeyPresent(span, AWS_LAMBDA_NAME)) {
        // Handling downstream Lambda as a service vs. an AWS resource:
        // - If the method call is "Invoke", we treat downstream Lambda as a service.
        // - Otherwise, we treat it as an AWS resource.
        //
        // This addresses a Lambda topology issue in Application Signals.
        // More context in PR:
        // https://github.com/aws-observability/aws-otel-python-instrumentation/pull/319
        //
        // NOTE: The environment variables LAMBDA_APPLICATION_SIGNALS_REMOTE_ENVIRONMENT was
        // introduced as part of this fix.
        // It is optional and allows users to override the default value if needed.
        if ("Invoke".equals(getRemoteOperation(span, RPC_METHOD))) {
          Optional<String> remoteService =
              getLambdaFunctionNameFromArn(
                  Optional.ofNullable(escapeDelimiters(span.getAttributes().get(AWS_LAMBDA_NAME))));
          builder.put(AWS_REMOTE_SERVICE, remoteService.get());

          String remoteEnvironment =
              Optional.ofNullable(System.getenv("LAMBDA_APPLICATION_SIGNALS_REMOTE_ENVIRONMENT"))
                  .filter(s -> !s.isEmpty())
                  .orElse("default");
          builder.put(AWS_REMOTE_ENVIRONMENT, "lambda:" + remoteEnvironment);
        } else {
          remoteResourceType = Optional.of(NORMALIZED_LAMBDA_SERVICE_NAME + "::Function");
          remoteResourceIdentifier =
              getLambdaFunctionNameFromArn(
                  Optional.ofNullable(escapeDelimiters(span.getAttributes().get(AWS_LAMBDA_NAME))));
          cloudformationPrimaryIdentifier =
              Optional.ofNullable(escapeDelimiters(span.getAttributes().get(AWS_LAMBDA_ARN)));
        }
      } else if (isKeyPresent(span, AWS_LAMBDA_RESOURCE_ID)) {
        remoteResourceType = Optional.of(NORMALIZED_LAMBDA_SERVICE_NAME + "::EventSourceMapping");
        remoteResourceIdentifier =
            Optional.ofNullable(escapeDelimiters(span.getAttributes().get(AWS_LAMBDA_RESOURCE_ID)));
      }
    } else if (isDBSpan(span)) {
      remoteResourceType = Optional.of(DB_CONNECTION_RESOURCE_TYPE);
      remoteResourceIdentifier = getDbConnection(span);
    }

    if (!cloudformationPrimaryIdentifier.isPresent()) {
      cloudformationPrimaryIdentifier = remoteResourceIdentifier;
    }

    if (remoteResourceType.isPresent() && remoteResourceIdentifier.isPresent()) {
      builder.put(AWS_REMOTE_RESOURCE_TYPE, remoteResourceType.get());
      builder.put(AWS_REMOTE_RESOURCE_IDENTIFIER, remoteResourceIdentifier.get());
      builder.put(AWS_CLOUDFORMATION_PRIMARY_IDENTIFIER, cloudformationPrimaryIdentifier.get());
    }
  }

  private static Optional<String> getLambdaFunctionNameFromArn(Optional<String> stringArn) {
    if (stringArn.isPresent() && stringArn.get().startsWith("arn:aws:lambda:")) {
      Arn resourceArn = Arn.fromString(stringArn.get());
      return Optional.of(resourceArn.getResource().toString().split(":")[1]);
    }
    return stringArn;
  }

  private static Optional<String> getSecretsManagerResourceNameFromArn(Optional<String> stringArn) {
    Arn resourceArn = Arn.fromString(stringArn.get());
    return Optional.of(resourceArn.getResource().toString().split(":")[1]);
  }

  private static Optional<String> getSfnResourceNameFromArn(Optional<String> stringArn) {
    Arn resourceArn = Arn.fromString(stringArn.get());
    return Optional.of(resourceArn.getResource().toString().split(":")[1]);
  }

  private static Optional<String> getSnsResourceNameFromArn(Optional<String> stringArn) {
    Arn resourceArn = Arn.fromString(stringArn.get());
    return Optional.of(resourceArn.getResource().toString());
  }

  /**
   * RemoteResourceIdentifier is populated with rule <code>
   *     ^[{db.name}|]?{address}[|{port}]?
   * </code>
   *
   * <pre>
   * {address} attribute is retrieved in priority order:
   * - {@link SemanticAttributes#SERVER_ADDRESS},
   * - {@link SemanticAttributes#NET_PEER_NAME},
   * - {@link SemanticAttributes#SERVER_SOCKET_ADDRESS}
   * - {@link SemanticAttributes#DB_CONNECTION_STRING}-Hostname
   * </pre>
   *
   * <pre>
   * {port} attribute is retrieved in priority order:
   * - {@link SemanticAttributes#SERVER_PORT},
   * - {@link SemanticAttributes#NET_PEER_PORT},
   * - {@link SemanticAttributes#SERVER_SOCKET_PORT}
   * - {@link SemanticAttributes#DB_CONNECTION_STRING}-Port
   * </pre>
   *
   * If address is not present, neither RemoteResourceType nor RemoteResourceIdentifier will be
   * provided.
   */
  private static Optional<String> getDbConnection(SpanData span) {
    String dbName = span.getAttributes().get(DB_NAME);
    Optional<String> dbConnection = Optional.empty();

    if (isKeyPresent(span, SERVER_ADDRESS)) {
      String serverAddress = span.getAttributes().get(SERVER_ADDRESS);
      Long serverPort = span.getAttributes().get(SERVER_PORT);
      dbConnection = buildDbConnection(serverAddress, serverPort);
    } else if (isKeyPresent(span, NET_PEER_NAME)) {
      String networkPeerAddress = span.getAttributes().get(NET_PEER_NAME);
      Long networkPeerPort = span.getAttributes().get(NET_PEER_PORT);
      dbConnection = buildDbConnection(networkPeerAddress, networkPeerPort);
    } else if (isKeyPresent(span, SERVER_SOCKET_ADDRESS)) {
      String serverSocketAddress = span.getAttributes().get(SERVER_SOCKET_ADDRESS);
      Long serverSocketPort = span.getAttributes().get(SERVER_SOCKET_PORT);
      dbConnection = buildDbConnection(serverSocketAddress, serverSocketPort);
    } else if (isKeyPresent(span, DB_CONNECTION_STRING)) {
      String connectionString = span.getAttributes().get(DB_CONNECTION_STRING);
      dbConnection = buildDbConnection(connectionString);
    }

    // return empty resource identifier if db server is not found
    if (dbConnection.isPresent() && dbName != null) {
      return Optional.of(escapeDelimiters(dbName) + "|" + dbConnection.get());
    }
    return dbConnection;
  }

  private static Optional<String> buildDbConnection(String address, Long port) {
    return Optional.of(escapeDelimiters(address) + (port != null ? "|" + port : ""));
  }

  private static Optional<String> buildDbConnection(String connectionString) {
    URI uri;
    String address;
    int port;
    try {
      uri = new URI(connectionString);
      address = uri.getHost();
      port = uri.getPort();
    } catch (URISyntaxException e) {
      logger.log(Level.FINEST, "invalid DB ConnectionString: ", connectionString);
      return Optional.empty();
    }

    if (address == null) {
      return Optional.empty();
    }
    return Optional.of(escapeDelimiters(address) + (port != -1 ? "|" + port : ""));
  }

  private static String escapeDelimiters(String input) {
    if (input == null) {
      return null;
    }
    return input.replace("^", "^^").replace("|", "^|");
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
    if (isKeyPresent(span, HTTP_RESPONSE_STATUS_CODE)) {
      return;
    }

    if (isKeyPresent(span, HTTP_STATUS_CODE)) {
      Long statusCode = span.getAttributes().get(HTTP_STATUS_CODE);
      builder.put(HTTP_RESPONSE_STATUS_CODE, statusCode);
      return;
    }

    if (isKeyPresent(span, HTTP_RESPONSE_STATUS_CODE)) {
      Long statusCode = span.getAttributes().get(HTTP_RESPONSE_STATUS_CODE);
      builder.put(HTTP_STATUS_CODE, statusCode);
      return;
    }

    Long statusCode = getAwsStatusCode(span);
    if (statusCode != null) {
      builder.put(HTTP_RESPONSE_STATUS_CODE, statusCode);
    }
  }

  private static void setRemoteDbUser(SpanData span, AttributesBuilder builder) {
    if (isDBSpan(span) && isKeyPresent(span, DB_USER)) {
      builder.put(AWS_REMOTE_DB_USER, span.getAttributes().get(DB_USER));
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

  /**
   * If no db.operation attribute provided in the span, we use db.statement to compute a valid
   * remote operation in a best-effort manner. To do this, we take the first substring of the
   * statement and compare to a regex list of known SQL keywords. The substring length is determined
   * by the longest known SQL keywords.
   */
  private static String getDBStatementRemoteOperation(
      SpanData span, AttributeKey<String> remoteOperationKey) {
    String remoteOperation = span.getAttributes().get(remoteOperationKey);
    if (remoteOperation == null) {
      remoteOperation = UNKNOWN_REMOTE_OPERATION;
    }

    // Remove all whitespace and newline characters from the beginning of remote_operation
    // and retrieve the first MAX_KEYWORD_LENGTH characters
    remoteOperation = remoteOperation.replaceFirst("^\\s+", "");
    if (remoteOperation.length() > MAX_KEYWORD_LENGTH) {
      remoteOperation = remoteOperation.substring(0, MAX_KEYWORD_LENGTH);
    }

    Matcher matcher = SQL_DIALECT_PATTERN.matcher(remoteOperation.toUpperCase());
    if (matcher.find() && !matcher.group(0).isEmpty()) {
      remoteOperation = matcher.group(0);
    } else {
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
