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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;

class AwsApplicationSignalsCustomizerProviderTest {

  @Test
  void setAdaptiveSamplingConfigFromString_validConfig() throws JsonProcessingException {
    assertThat(AwsApplicationSignalsCustomizerProvider.parseConfigString("version: 1").getVersion())
        .isEqualTo(1);
  }

  @Test
  void setAdaptiveSamplingConfigFromString_nullConfig() {
    assertThatNoException()
        .isThrownBy(() -> AwsApplicationSignalsCustomizerProvider.parseConfigString(null));
  }

  @Test
  void setAdaptiveSamplingConfigFromString_missingVersion() {
    assertThatException()
        .isThrownBy(() -> AwsApplicationSignalsCustomizerProvider.parseConfigString(""));
  }

  @Test
  void setAdaptiveSamplingConfigFromString_unsupportedVersion() {
    assertThatException()
        .isThrownBy(
            () -> AwsApplicationSignalsCustomizerProvider.parseConfigString("{version: 5000.1}"));
  }

  @Test
  void setAdaptiveSamplingConfigFromString_invalidYaml() {
    assertThatException()
        .isThrownBy(
            () ->
                AwsApplicationSignalsCustomizerProvider.parseConfigString(
                    "{version: 1, invalid: yaml: structure}"));
  }
}
