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

package software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11;

import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsExperimentalAttributes.AWS_AGENT_ID;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsExperimentalAttributes.AWS_AUTH_ACCESS_KEY;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsExperimentalAttributes.AWS_BEDROCK_RUNTIME_MODEL_ID;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsExperimentalAttributes.AWS_BEDROCK_SYSTEM;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsExperimentalAttributes.AWS_GUARDRAIL_ARN;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsExperimentalAttributes.AWS_GUARDRAIL_ID;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsExperimentalAttributes.AWS_KNOWLEDGE_BASE_ID;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsExperimentalAttributes.AWS_LAMBDA_ARN;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsExperimentalAttributes.AWS_LAMBDA_NAME;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsExperimentalAttributes.AWS_LAMBDA_RESOURCE_ID;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsExperimentalAttributes.AWS_SECRET_ARN;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsExperimentalAttributes.AWS_SNS_TOPIC_ARN;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsExperimentalAttributes.AWS_STATE_MACHINE_ARN;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsExperimentalAttributes.AWS_STEP_FUNCTIONS_ACTIVITY_ARN;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsExperimentalAttributes.AWS_STREAM_ARN;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsExperimentalAttributes.AWS_TABLE_ARN;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsExperimentalAttributes.GEN_AI_REQUEST_MAX_TOKENS;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsExperimentalAttributes.GEN_AI_REQUEST_TEMPERATURE;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsExperimentalAttributes.GEN_AI_REQUEST_TOP_P;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsExperimentalAttributes.GEN_AI_RESPONSE_FINISH_REASONS;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsExperimentalAttributes.GEN_AI_USAGE_INPUT_TOKENS;
import static software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11.AwsExperimentalAttributes.GEN_AI_USAGE_OUTPUT_TOKENS;

import com.amazonaws.Request;
import com.amazonaws.Response;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.handlers.HandlerContextKey;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor;
import java.util.Objects;
import java.util.function.Function;
import javax.annotation.Nullable;

class AwsSdkExperimentalAttributesExtractor
    implements AttributesExtractor<Request<?>, Response<?>> {
  private static final String BEDROCK_SERVICE = "AmazonBedrock";
  private static final String BEDROCK_AGENT_SERVICE = "AWSBedrockAgent";
  private static final String BEDROCK_AGENT_RUNTIME_SERVICE = "AWSBedrockAgentRuntime";
  private static final String BEDROCK_RUNTIME_SERVICE = "AmazonBedrockRuntime";
  private static final HandlerContextKey<AWSCredentials> AWS_CREDENTIALS =
      new HandlerContextKey<AWSCredentials>("AWSCredentials");

  AwsSdkExperimentalAttributesExtractor() {}

  @Override
  public void onStart(AttributesBuilder attributes, Context parentContext, Request<?> request) {

    Object originalRequest = request.getOriginalRequest();
    String requestClassName = originalRequest.getClass().getSimpleName();

    AWSCredentials credentials = request.getHandlerContext(AWS_CREDENTIALS);
    if (credentials != null) {
      String accessKeyId = credentials.getAWSAccessKeyId();
      if (accessKeyId != null) {
        attributes.put(AWS_AUTH_ACCESS_KEY, accessKeyId);
      }
    }

    setAttribute(attributes, AWS_STREAM_ARN, originalRequest, RequestAccess::getStreamArn);
    setAttribute(
        attributes, AWS_STATE_MACHINE_ARN, originalRequest, RequestAccess::getStateMachineArn);
    setAttribute(
        attributes,
        AWS_STEP_FUNCTIONS_ACTIVITY_ARN,
        originalRequest,
        RequestAccess::getStepFunctionsActivityArn);
    setAttribute(attributes, AWS_SNS_TOPIC_ARN, originalRequest, RequestAccess::getSnsTopicArn);
    setAttribute(attributes, AWS_SECRET_ARN, originalRequest, RequestAccess::getSecretArn);
    setAttribute(attributes, AWS_LAMBDA_NAME, originalRequest, RequestAccess::getLambdaName);
    setAttribute(
        attributes, AWS_LAMBDA_RESOURCE_ID, originalRequest, RequestAccess::getLambdaResourceId);
    // Get serviceName defined in the AWS Java SDK V1 Request class.
    String serviceName = request.getServiceName();
    // Extract request attributes only for Bedrock services.
    if (isBedrockService(serviceName)) {
      bedrockOnStart(attributes, originalRequest, requestClassName, serviceName);
    }
  }

  @Override
  public void onEnd(
      AttributesBuilder attributes,
      Context context,
      Request<?> request,
      @Nullable Response<?> response,
      @Nullable Throwable error) {
    if (response != null) {
      Object awsResp = response.getAwsResponse();
      setAttribute(attributes, AWS_TABLE_ARN, awsResp, RequestAccess::getTableArn);
      setAttribute(attributes, AWS_LAMBDA_ARN, awsResp, RequestAccess::getLambdaArn);
      setAttribute(attributes, AWS_STATE_MACHINE_ARN, awsResp, RequestAccess::getStateMachineArn);
      setAttribute(
          attributes,
          AWS_STEP_FUNCTIONS_ACTIVITY_ARN,
          awsResp,
          RequestAccess::getStepFunctionsActivityArn);
      setAttribute(attributes, AWS_SNS_TOPIC_ARN, awsResp, RequestAccess::getSnsTopicArn);
      setAttribute(attributes, AWS_SECRET_ARN, awsResp, RequestAccess::getSecretArn);
      // Get serviceName defined in the AWS Java SDK V1 Request class.
      String serviceName = request.getServiceName();
      // Extract response attributes for Bedrock services
      if (awsResp != null && isBedrockService(serviceName)) {
        bedrockOnEnd(attributes, awsResp, serviceName);
      }
    }
  }

  private static void bedrockOnStart(
      AttributesBuilder attributes,
      Object originalRequest,
      String requestClassName,
      String serviceName) {
    switch (serviceName) {
      case BEDROCK_SERVICE:
        setAttribute(attributes, AWS_GUARDRAIL_ID, originalRequest, RequestAccess::getGuardrailId);
        break;
      case BEDROCK_AGENT_SERVICE:
        AwsBedrockResourceType resourceType =
            AwsBedrockResourceType.getRequestType(requestClassName);
        if (resourceType != null) {
          setAttribute(
              attributes,
              resourceType.getKeyAttribute(),
              originalRequest,
              resourceType.getAttributeValueAccessor());
        }
        break;
      case BEDROCK_AGENT_RUNTIME_SERVICE:
        setAttribute(attributes, AWS_AGENT_ID, originalRequest, RequestAccess::getAgentId);
        setAttribute(
            attributes, AWS_KNOWLEDGE_BASE_ID, originalRequest, RequestAccess::getKnowledgeBaseId);
        break;
      case BEDROCK_RUNTIME_SERVICE:
        if (!Objects.equals(requestClassName, "InvokeModelRequest")) {
          break;
        }
        attributes.put(AWS_BEDROCK_SYSTEM, "aws.bedrock");
        Function<Object, String> getter = RequestAccess::getModelId;
        String modelId = getter.apply(originalRequest);
        attributes.put(AWS_BEDROCK_RUNTIME_MODEL_ID, modelId);

        setAttribute(
            attributes, GEN_AI_REQUEST_MAX_TOKENS, originalRequest, RequestAccess::getMaxTokens);
        setAttribute(
            attributes, GEN_AI_REQUEST_TEMPERATURE, originalRequest, RequestAccess::getTemperature);
        setAttribute(attributes, GEN_AI_REQUEST_TOP_P, originalRequest, RequestAccess::getTopP);
        setAttribute(
            attributes, GEN_AI_USAGE_INPUT_TOKENS, originalRequest, RequestAccess::getInputTokens);
        break;
      default:
        break;
    }
  }

  private static void bedrockOnEnd(
      AttributesBuilder attributes, Object awsResp, String serviceName) {
    switch (serviceName) {
      case BEDROCK_SERVICE:
        setAttribute(attributes, AWS_GUARDRAIL_ID, awsResp, RequestAccess::getGuardrailId);
        setAttribute(attributes, AWS_GUARDRAIL_ARN, awsResp, RequestAccess::getGuardrailArn);
        break;
      case BEDROCK_AGENT_SERVICE:
        String responseClassName = awsResp.getClass().getSimpleName();
        AwsBedrockResourceType resourceType =
            AwsBedrockResourceType.getResponseType(responseClassName);
        if (resourceType != null) {
          setAttribute(
              attributes,
              resourceType.getKeyAttribute(),
              awsResp,
              resourceType.getAttributeValueAccessor());
        }
        break;
      case BEDROCK_AGENT_RUNTIME_SERVICE:
        setAttribute(attributes, AWS_AGENT_ID, awsResp, RequestAccess::getAgentId);
        setAttribute(attributes, AWS_KNOWLEDGE_BASE_ID, awsResp, RequestAccess::getKnowledgeBaseId);
        break;
      case BEDROCK_RUNTIME_SERVICE:
        if (!Objects.equals(awsResp.getClass().getSimpleName(), "InvokeModelResult")) {
          break;
        }

        setAttribute(attributes, GEN_AI_USAGE_INPUT_TOKENS, awsResp, RequestAccess::getInputTokens);
        setAttribute(
            attributes, GEN_AI_USAGE_OUTPUT_TOKENS, awsResp, RequestAccess::getOutputTokens);
        setAttribute(
            attributes, GEN_AI_RESPONSE_FINISH_REASONS, awsResp, RequestAccess::getFinishReasons);
        break;
      default:
        break;
    }
  }

  private static boolean isBedrockService(String serviceName) {
    // Check if the serviceName belongs to Bedrock Services defined in AWS Java SDK V1.
    // For example <a
    // href="https://github.com/aws/aws-sdk-java/blob/38031248a696468e19a4670c0c4585637d5e7cc6/aws-java-sdk-bedrock/src/main/java/com/amazonaws/services/bedrock/AmazonBedrock.java#L34">AmazonBedrock</a>
    return serviceName.equals(BEDROCK_SERVICE)
        || serviceName.equals(BEDROCK_AGENT_SERVICE)
        || serviceName.equals(BEDROCK_AGENT_RUNTIME_SERVICE)
        || serviceName.equals(BEDROCK_RUNTIME_SERVICE);
  }

  private static void setAttribute(
      AttributesBuilder attributes,
      AttributeKey<String> key,
      Object request,
      Function<Object, String> getter) {
    String value = getter.apply(request);
    if (value != null) {
      attributes.put(key, value);
    }
  }
}
