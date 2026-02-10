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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BedrockJsonParserTest {

  @Test
  void shouldParseSimpleJsonObject() {
    String json = "{\"key\":\"value\"}";

    Map<String, Object> actual = BedrockJsonParser.parse(json).getJsonBody();
    Map<String, Object> expected = Map.of("key", "value");

    assertThat(actual).isEqualTo(expected);
  }

  @Test
  void shouldParseNestedJsonObject() {
    String json = "{\"parent\":{\"child\":\"value\"}}";

    Map<String, Object> actual = BedrockJsonParser.parse(json).getJsonBody();
    Map<String, Object> expected = Map.of("parent", Map.of("child", "value"));

    assertThat(actual).isEqualTo(expected);
  }

  @Test
  void shouldParseJsonArray() {
    String json = "{\"array\":[1, \"two\", 1.0]}";

    Map<String, Object> actual = BedrockJsonParser.parse(json).getJsonBody();
    Map<String, Object> expected = Map.of("array", List.of(1, "two", 1.0));

    assertThat(actual).isEqualTo(expected);
  }

  @Test
  void shouldParseEscapeSequences() {
    String json =
        "{\"escaped\":\"Line1\\nLine2\\tTabbed\\\"Quoted\\\"\\bBackspace\\fFormfeed\\rCarriageReturn\\\\Backslash\\/Slash\\u0041\"}";

    Map<String, Object> actual = BedrockJsonParser.parse(json).getJsonBody();
    Map<String, Object> expected =
        Map.of(
            "escaped",
            "Line1\nLine2\tTabbed\"Quoted\"\bBackspace\fFormfeed\rCarriageReturn\\Backslash/SlashA");

    assertThat(actual).isEqualTo(expected);
  }

  @Test
  void shouldThrowExceptionForMalformedJson() {
    String malformedJson = "{\"key\":value}";

    assertThatThrownBy(() -> BedrockJsonParser.parse(malformedJson))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unexpected character");
  }

  @Test
  void shouldResolvePathInJsonObject() {
    String json = "{\"parent\":{\"child\":{\"key\":\"value\"}}}";

    BedrockJsonParser.LlmJson parsedJson = BedrockJsonParser.parse(json);
    Object resolvedValue =
        BedrockJsonParser.JsonPathResolver.resolvePath(parsedJson, "/parent/child/key");

    assertThat(resolvedValue).isEqualTo("value");
  }

  @Test
  void shouldResolvePathInJsonArray() {
    String json = "{\"array\":[{\"key\":\"value1\"}, {\"key\":\"value2\"}]}";

    BedrockJsonParser.LlmJson parsedJson = BedrockJsonParser.parse(json);
    Object resolvedValue =
        BedrockJsonParser.JsonPathResolver.resolvePath(parsedJson, "/array/1/key");

    assertThat(resolvedValue).isEqualTo("value2");
  }

  @Test
  void shouldReturnNullForInvalidPathResolution() {
    String json = "{\"parent\":{\"child\":{\"key\":\"value\"}}}";

    BedrockJsonParser.LlmJson parsedJson = BedrockJsonParser.parse(json);
    Object resolvedValue =
        BedrockJsonParser.JsonPathResolver.resolvePath(parsedJson, "/invalid/path");

    assertThat(resolvedValue).isNull();
  }
}
