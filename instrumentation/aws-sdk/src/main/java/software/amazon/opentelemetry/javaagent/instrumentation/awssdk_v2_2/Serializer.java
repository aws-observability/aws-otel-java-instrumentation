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

package software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.SdkPojo;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.protocols.core.ProtocolMarshaller;
import software.amazon.awssdk.utils.IoUtils;
import software.amazon.awssdk.utils.StringUtils;

/*
 * This class contains both patching logic and copied OTel aws-sdk-2.2 code.
 */
class Serializer {

  @Nullable
  String serialize(Object target) {

    if (target == null) {
      return null;
    }

    // accounts for new AWS request types added by patch
    if (target instanceof SdkPojo) {
      return serialize((SdkPojo) target);
    }

    if (target instanceof Collection) {
      return serialize((Collection<?>) target);
    }
    if (target instanceof Map) {
      return serialize(((Map<?, ?>) target).keySet());
    }
    // simple type
    return target.toString();
  }

  @Nullable
  String serialize(String attributeName, Object target) {
    try {
      // Extract JSON string from target if it is a Bedrock Runtime JSON blob
      String jsonString;
      if (target instanceof SdkBytes) {
        jsonString = ((SdkBytes) target).asUtf8String();
      } else {
        if (target != null) {
          return target.toString();
        }
        return null;
      }

      // Parse the LLM JSON string into a Map
      BedrockJsonParser.LlmJson llmJson = BedrockJsonParser.parse(jsonString);

      // Use attribute name to extract the corresponding value
      switch (attributeName) {
        case "gen_ai.request.max_tokens":
          return getMaxTokens(llmJson);
        case "gen_ai.request.temperature":
          return getTemperature(llmJson);
        case "gen_ai.request.top_p":
          return getTopP(llmJson);
        case "gen_ai.response.finish_reasons":
          return getFinishReasons(llmJson);
        case "gen_ai.usage.input_tokens":
          return getInputTokens(llmJson);
        case "gen_ai.usage.output_tokens":
          return getOutputTokens(llmJson);
        default:
          return null;
      }
    } catch (RuntimeException e) {
      return null;
    }
  }

  @Nullable
  private static String serialize(SdkPojo sdkPojo) {
    ProtocolMarshaller<SdkHttpFullRequest> marshaller =
        AwsJsonProtocolFactoryAccess.createMarshaller();
    if (marshaller == null) {
      return null;
    }
    Optional<ContentStreamProvider> optional = marshaller.marshall(sdkPojo).contentStreamProvider();
    return optional
        .map(
            csp -> {
              try (InputStream cspIs = csp.newStream()) {
                return IoUtils.toUtf8String(cspIs);
              } catch (IOException e) {
                return null;
              }
            })
        .orElse(null);
  }

  private String serialize(Collection<?> collection) {
    String serialized = collection.stream().map(this::serialize).collect(Collectors.joining(","));
    return (StringUtils.isEmpty(serialized) ? null : "[" + serialized + "]");
  }

  @Nullable
  private static String approximateTokenCount(
      BedrockJsonParser.LlmJson jsonBody, String... textPaths) {
    return Arrays.stream(textPaths)
        .map(
            path -> {
              Object value = BedrockJsonParser.JsonPathResolver.resolvePath(jsonBody, path);
              if (value instanceof String) {
                int tokenEstimate = (int) Math.ceil(((String) value).length() / 6.0);
                return Integer.toString(tokenEstimate);
              }
              return null;
            })
        .filter(Objects::nonNull)
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
  private static String getMaxTokens(BedrockJsonParser.LlmJson jsonBody) {
    Object value =
        BedrockJsonParser.JsonPathResolver.resolvePath(
            jsonBody,
            "/max_tokens",
            "/max_gen_len",
            "/textGenerationConfig/maxTokenCount",
            "inferenceConfig/max_new_tokens");
    return value != null ? String.valueOf(value) : null;
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
  private static String getTemperature(BedrockJsonParser.LlmJson jsonBody) {
    Object value =
        BedrockJsonParser.JsonPathResolver.resolvePath(
            jsonBody,
            "/temperature",
            "/textGenerationConfig/temperature",
            "/inferenceConfig/temperature");
    return value != null ? String.valueOf(value) : null;
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
  private static String getTopP(BedrockJsonParser.LlmJson jsonBody) {
    Object value =
        BedrockJsonParser.JsonPathResolver.resolvePath(
            jsonBody, "/top_p", "/p", "/textGenerationConfig/topP", "/inferenceConfig/top_p");
    return value != null ? String.valueOf(value) : null;
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
  private static String getFinishReasons(BedrockJsonParser.LlmJson jsonBody) {
    Object value =
        BedrockJsonParser.JsonPathResolver.resolvePath(
            jsonBody,
            "/stopReason",
            "/finish_reason",
            "/stop_reason",
            "/results/0/completionReason",
            "/generations/0/finish_reason",
            "/choices/0/finish_reason",
            "/outputs/0/stop_reason");

    return value != null ? "[" + value + "]" : null;
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
  private static String getInputTokens(BedrockJsonParser.LlmJson jsonBody) {
    // Try direct tokens counts first
    Object directCount =
        BedrockJsonParser.JsonPathResolver.resolvePath(
            jsonBody,
            "/inputTextTokenCount",
            "/prompt_token_count",
            "/usage/input_tokens",
            "/usage/prompt_tokens",
            "/usage/inputTokens");

    if (directCount != null) {
      return String.valueOf(directCount);
    }

    // Fall back to token approximation
    Object approxTokenCount = approximateTokenCount(jsonBody, "/prompt", "/message");

    return approxTokenCount != null ? String.valueOf(approxTokenCount) : null;
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
  private static String getOutputTokens(BedrockJsonParser.LlmJson jsonBody) {
    // Try direct token counts first
    Object directCount =
        BedrockJsonParser.JsonPathResolver.resolvePath(
            jsonBody,
            "/generation_token_count",
            "/results/0/tokenCount",
            "/usage/output_tokens",
            "/usage/completion_tokens",
            "/usage/outputTokens");

    if (directCount != null) {
      return String.valueOf(directCount);
    }

    // Fall back to token approximation
    Object approxTokenCount = approximateTokenCount(jsonBody, "/text", "/outputs/0/text");

    return approxTokenCount != null ? String.valueOf(approxTokenCount) : null;
  }
}
