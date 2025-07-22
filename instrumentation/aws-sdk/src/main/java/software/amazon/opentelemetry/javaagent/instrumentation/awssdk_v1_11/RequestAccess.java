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

/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modifications Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;
import javax.annotation.Nullable;

final class RequestAccess {

  private static final ClassValue<RequestAccess> REQUEST_ACCESSORS =
      new ClassValue<RequestAccess>() {
        @Override
        protected RequestAccess computeValue(Class<?> type) {
          return new RequestAccess(type);
        }
      };

  // 2025-07-22: Amazon addition
  @Nullable
  private static BedrockJsonParser.LlmJson parseTargetBody(ByteBuffer buffer) {
    try {
      byte[] bytes;
      // Create duplicate to avoid mutating the original buffer position
      ByteBuffer duplicate = buffer.duplicate();
      if (buffer.hasArray()) {
        bytes =
            Arrays.copyOfRange(
                duplicate.array(),
                duplicate.arrayOffset(),
                duplicate.arrayOffset() + duplicate.remaining());
      } else {
        bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
      }
      String jsonString = new String(bytes, StandardCharsets.UTF_8); // Convert to String
      return BedrockJsonParser.parse(jsonString);
    } catch (RuntimeException e) {
      return null;
    }
  }

  @Nullable
  private static BedrockJsonParser.LlmJson getJsonBody(Object target) {
    if (target == null) {
      return null;
    }

    RequestAccess access = REQUEST_ACCESSORS.get(target.getClass());
    ByteBuffer bodyBuffer = invokeOrNullGeneric(access.getBody, target, ByteBuffer.class);
    if (bodyBuffer == null) {
      return null;
    }

    return parseTargetBody(bodyBuffer);
  }

  @Nullable
  private static String findFirstMatchingPath(BedrockJsonParser.LlmJson jsonBody, String... paths) {
    if (jsonBody == null) {
      return null;
    }

    return Stream.of(paths)
        .map(path -> BedrockJsonParser.JsonPathResolver.resolvePath(jsonBody, path))
        .filter(Objects::nonNull)
        .map(Object::toString)
        .findFirst()
        .orElse(null);
  }

  @Nullable
  private static String approximateTokenCount(
      BedrockJsonParser.LlmJson jsonBody, String... textPaths) {
    if (jsonBody == null) {
      return null;
    }

    return Stream.of(textPaths)
        .map(path -> BedrockJsonParser.JsonPathResolver.resolvePath(jsonBody, path))
        .filter(value -> value instanceof String)
        .map(value -> Integer.toString((int) Math.ceil(((String) value).length() / 6.0)))
        .findFirst()
        .orElse(null);
  }

  // Model -> Path Mapping:
  // Amazon Nova -> "/inferenceConfig/max_new_tokens"
  // Amazon Titan -> "/textGenerationConfig/maxTokenCount"
  // Anthropic Claude -> "/max_tokens"
  // Cohere Command -> "/max_tokens"
  // Cohere Command R -> "/max_tokens"
  // AI21 Jamba -> "/max_tokens"
  // Meta Llama -> "/max_gen_len"
  // Mistral AI -> "/max_tokens"
  @Nullable
  static String getMaxTokens(Object target) {
    BedrockJsonParser.LlmJson jsonBody = getJsonBody(target);
    return findFirstMatchingPath(
        jsonBody,
        "/max_tokens",
        "/max_gen_len",
        "/textGenerationConfig/maxTokenCount",
        "/inferenceConfig/max_new_tokens");
  }

  // Model -> Path Mapping:
  // Amazon Nova -> "/inferenceConfig/temperature"
  // Amazon Titan -> "/textGenerationConfig/temperature"
  // Anthropic Claude -> "/temperature"
  // Cohere Command -> "/temperature"
  // Cohere Command R -> "/temperature"
  // AI21 Jamba -> "/temperature"
  // Meta Llama -> "/temperature"
  // Mistral AI -> "/temperature"
  @Nullable
  static String getTemperature(Object target) {
    BedrockJsonParser.LlmJson jsonBody = getJsonBody(target);
    return findFirstMatchingPath(
        jsonBody,
        "/temperature",
        "/textGenerationConfig/temperature",
        "inferenceConfig/temperature");
  }

  // Model -> Path Mapping:
  // Amazon Nova -> "/inferenceConfig/top_p"
  // Amazon Titan -> "/textGenerationConfig/topP"
  // Anthropic Claude -> "/top_p"
  // Cohere Command -> "/p"
  // Cohere Command R -> "/p"
  // AI21 Jamba -> "/top_p"
  // Meta Llama -> "/top_p"
  // Mistral AI -> "/top_p"
  @Nullable
  static String getTopP(Object target) {
    BedrockJsonParser.LlmJson jsonBody = getJsonBody(target);
    return findFirstMatchingPath(
        jsonBody, "/top_p", "/p", "/textGenerationConfig/topP", "/inferenceConfig/top_p");
  }

  // Model -> Path Mapping:
  // Amazon Nova -> "/usage/inputTokens"
  // Amazon Titan -> "/inputTextTokenCount"
  // Anthropic Claude -> "/usage/input_tokens"
  // Cohere Command -> "/prompt"
  // Cohere Command R -> "/message"
  // AI21 Jamba -> "/usage/prompt_tokens"
  // Meta Llama -> "/prompt_token_count"
  // Mistral AI -> "/prompt"
  @Nullable
  static String getInputTokens(Object target) {
    BedrockJsonParser.LlmJson jsonBody = getJsonBody(target);
    if (jsonBody == null) {
      return null;
    }

    // Try direct token counts first
    String directCount =
        findFirstMatchingPath(
            jsonBody,
            "/inputTextTokenCount",
            "/prompt_token_count",
            "/usage/input_tokens",
            "/usage/prompt_tokens",
            "/usage/inputTokens");

    if (directCount != null && !directCount.equals("null")) {
      return directCount;
    }

    // Fall back to token approximation
    return approximateTokenCount(jsonBody, "/prompt", "/message");
  }

  // Model -> Path Mapping:
  // Amazon Nova -> "/usage/outputTokens"
  // Amazon Titan -> "/results/0/tokenCount"
  // Anthropic Claude -> "/usage/output_tokens"
  // Cohere Command -> "/generations/0/text"
  // Cohere Command R -> "/text"
  // AI21 Jamba -> "/usage/completion_tokens"
  // Meta Llama -> "/generation_token_count"
  // Mistral AI -> "/outputs/0/text"
  @Nullable
  static String getOutputTokens(Object target) {
    BedrockJsonParser.LlmJson jsonBody = getJsonBody(target);
    if (jsonBody == null) {
      return null;
    }

    // Try direct token counts first
    String directCount =
        findFirstMatchingPath(
            jsonBody,
            "/generation_token_count",
            "/results/0/tokenCount",
            "/usage/output_tokens",
            "/usage/completion_tokens",
            "/usage/outputTokens");

    if (directCount != null && !directCount.equals("null")) {
      return directCount;
    }

    // Fall back to token approximation
    return approximateTokenCount(jsonBody, "/text", "/outputs/0/text");
  }

  // Model -> Path Mapping:
  // Amazon Nova -> "/stopReason"
  // Amazon Titan -> "/results/0/completionReason"
  // Anthropic Claude -> "/stop_reason"
  // Cohere Command -> "/generations/0/finish_reason"
  // Cohere Command R -> "/finish_reason"
  // AI21 Jamba -> "/choices/0/finish_reason"
  // Meta Llama -> "/stop_reason"
  // Mistral AI -> "/outputs/0/stop_reason"
  @Nullable
  static String getFinishReasons(Object target) {
    BedrockJsonParser.LlmJson jsonBody = getJsonBody(target);
    String finishReason =
        findFirstMatchingPath(
            jsonBody,
            "/stopReason",
            "/finish_reason",
            "/stop_reason",
            "/results/0/completionReason",
            "/generations/0/finish_reason",
            "/choices/0/finish_reason",
            "/outputs/0/stop_reason");

    return finishReason != null ? "[" + finishReason + "]" : null;
  }

  @Nullable
  static String getLambdaName(Object request) {
    if (request == null) {
      return null;
    }
    RequestAccess access = REQUEST_ACCESSORS.get(request.getClass());
    return invokeOrNull(access.getLambdaName, request);
  }

  @Nullable
  static String getLambdaArn(Object request) {
    if (request == null) {
      return null;
    }
    return findNestedAccessorOrNull(request, "getConfiguration", "getFunctionArn");
  }

  @Nullable
  static String getLambdaResourceId(Object request) {
    if (request == null) {
      return null;
    }
    RequestAccess access = REQUEST_ACCESSORS.get(request.getClass());
    return invokeOrNull(access.getLambdaResourceId, request);
  }

  @Nullable
  static String getSecretArn(Object request) {
    if (request == null) {
      return null;
    }
    RequestAccess access = REQUEST_ACCESSORS.get(request.getClass());
    return invokeOrNull(access.getSecretArn, request);
  }

  @Nullable
  static String getSnsTopicArn(Object request) {
    if (request == null) {
      return null;
    }
    RequestAccess access = REQUEST_ACCESSORS.get(request.getClass());
    return invokeOrNull(access.getSnsTopicArn, request);
  }

  @Nullable
  static String getStepFunctionsActivityArn(Object request) {
    if (request == null) {
      return null;
    }
    RequestAccess access = REQUEST_ACCESSORS.get(request.getClass());
    return invokeOrNull(access.getStepFunctionsActivityArn, request);
  }

  @Nullable
  static String getStateMachineArn(Object request) {
    if (request == null) {
      return null;
    }
    RequestAccess access = REQUEST_ACCESSORS.get(request.getClass());
    return invokeOrNull(access.getStateMachineArn, request);
  }

  @Nullable
  static String getTableArn(Object request) {
    if (request == null) {
      return null;
    }
    return findNestedAccessorOrNull(request, "getTable", "getTableArn");
  }

  @Nullable
  static String getStreamArn(Object request) {
    if (request == null) {
      return null;
    }
    RequestAccess access = REQUEST_ACCESSORS.get(request.getClass());
    return invokeOrNull(access.getStreamArn, request);
  }

  @Nullable
  static String getAgentId(Object request) {
    if (request == null) {
      return null;
    }
    RequestAccess access = REQUEST_ACCESSORS.get(request.getClass());
    return invokeOrNull(access.getAgentId, request);
  }

  @Nullable
  static String getKnowledgeBaseId(Object request) {
    if (request == null) {
      return null;
    }
    RequestAccess access = REQUEST_ACCESSORS.get(request.getClass());
    return invokeOrNull(access.getKnowledgeBaseId, request);
  }

  @Nullable
  static String getDataSourceId(Object request) {
    if (request == null) {
      return null;
    }
    RequestAccess access = REQUEST_ACCESSORS.get(request.getClass());
    return invokeOrNull(access.getDataSourceId, request);
  }

  @Nullable
  static String getGuardrailId(Object request) {
    if (request == null) {
      return null;
    }
    RequestAccess access = REQUEST_ACCESSORS.get(request.getClass());
    return invokeOrNull(access.getGuardrailId, request);
  }

  @Nullable
  static String getGuardrailArn(Object request) {
    if (request == null) {
      return null;
    }
    return findNestedAccessorOrNull(request, "getGuardrailArn");
  }

  @Nullable
  static String getModelId(Object request) {
    if (request == null) {
      return null;
    }
    RequestAccess access = REQUEST_ACCESSORS.get(request.getClass());
    return invokeOrNull(access.getModelId, request);
  }

  // End of Amazon addition

  @Nullable
  private static String invokeOrNull(@Nullable MethodHandle method, Object obj) {
    if (method == null) {
      return null;
    }
    try {
      return (String) method.invoke(obj);
    } catch (Throwable t) {
      return null;
    }
  }

  // 2025-07-22: Amazon addition
  @Nullable
  private static <T> T invokeOrNullGeneric(
      @Nullable MethodHandle method, Object obj, Class<T> returnType) {
    if (method == null) {
      return null;
    }
    try {
      return returnType.cast(method.invoke(obj));
    } catch (Throwable e) {
      return null;
    }
  }

  @Nullable private final MethodHandle getStreamArn;
  @Nullable private final MethodHandle getAgentId;
  @Nullable private final MethodHandle getKnowledgeBaseId;
  @Nullable private final MethodHandle getDataSourceId;
  @Nullable private final MethodHandle getGuardrailId;
  @Nullable private final MethodHandle getModelId;
  @Nullable private final MethodHandle getBody;
  @Nullable private final MethodHandle getStateMachineArn;
  @Nullable private final MethodHandle getStepFunctionsActivityArn;
  @Nullable private final MethodHandle getSnsTopicArn;
  @Nullable private final MethodHandle getSecretArn;
  @Nullable private final MethodHandle getLambdaName;
  @Nullable private final MethodHandle getLambdaResourceId;

  private RequestAccess(Class<?> clz) {
    getStreamArn = findAccessorOrNull(clz, "getStreamARN", String.class);
    getAgentId = findAccessorOrNull(clz, "getAgentId", String.class);
    getKnowledgeBaseId = findAccessorOrNull(clz, "getKnowledgeBaseId", String.class);
    getDataSourceId = findAccessorOrNull(clz, "getDataSourceId", String.class);
    getGuardrailId = findAccessorOrNull(clz, "getGuardrailId", String.class);
    getModelId = findAccessorOrNull(clz, "getModelId", String.class);
    getBody = findAccessorOrNull(clz, "getBody", ByteBuffer.class);
    getStateMachineArn = findAccessorOrNull(clz, "getStateMachineArn", String.class);
    getStepFunctionsActivityArn = findAccessorOrNull(clz, "getActivityArn", String.class);
    getSnsTopicArn = findAccessorOrNull(clz, "getTopicArn", String.class);
    getSecretArn = findAccessorOrNull(clz, "getARN", String.class);
    getLambdaName = findAccessorOrNull(clz, "getFunctionName", String.class);
    getLambdaResourceId = findAccessorOrNull(clz, "getUUID", String.class);
  }

  /**
   * Uses Java reflection to find a getter method on a class and create a MethodHandle for it.
   *
   * @param clz The class to search for the method
   * @param methodName The name of the getter method (e.g., "getStreamARN")
   * @param returnType The expected return type of the method
   * @return A MethodHandle for the method, or null if not found
   *     <p>Example: For class PutRecordRequest with method "getStreamARN":
   *     findAccessorOrNull(PutRecordRequest.class, "getStreamARN", String.class) Creates a method
   *     handle that can invoke getStreamARN() on PutRecordRequest instances
   */
  @Nullable
  private static MethodHandle findAccessorOrNull(
      Class<?> clz, String methodName, Class<?> returnType) {
    try {
      // Uses MethodHandles.publicLookup() to get access to public methods
      // findVirtual finds an instance method with the given name and type
      // methodType creates a method type with no parameters and the specified return type
      return MethodHandles.publicLookup()
          .findVirtual(clz, methodName, MethodType.methodType(returnType));
    } catch (Throwable t) {
      // Returns null if method doesn't exist or can't be accessed
      return null;
    }
  }

  /**
   * Uses reflection to navigate through nested method calls and extract a String value. Unlike
   * using method handles, this supports chained method calls where each method might return a
   * different type of object.
   *
   * @param obj The initial object to start method calls from
   * @param methodNames Variable list of method names to call in sequence
   * @return The final String value, or null if any method in the chain fails or returns null
   *     <p>Example: For Lambda ARN: findNestedAccessorOrNull(request, "getConfiguration",
   *     "getFunctionArn") - First calls request.getConfiguration() to get a Configuration object,
   *     then calls configuration.getFunctionArn() to get the ARN string
   */
  @Nullable
  private static String findNestedAccessorOrNull(Object obj, String... methodNames) {
    Object current = obj;
    for (String methodName : methodNames) {
      if (current == null) {
        return null;
      }
      try {
        Method method = current.getClass().getMethod(methodName);
        current = method.invoke(current);
      } catch (Exception e) {
        return null;
      }
    }
    return (current instanceof String) ? (String) current : null;
  }
  // End of Amazon addition
}
