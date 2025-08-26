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
import io.opentelemetry.contrib.awsxray.AwsXrayAdaptiveSamplingConfig;
import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
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

  @Test
  void setAdaptiveSamplingConfigFromFile_validYaml()
      throws JsonProcessingException, URISyntaxException {
    // Get the resource file path
    URL resourceUrl =
        getClass().getClassLoader().getResource("adaptive-sampling-config-valid.yaml");
    assertThat(resourceUrl).isNotNull();

    // Get the absolute file path
    File configFile = new File(resourceUrl.toURI());
    String absolutePath = configFile.getAbsolutePath();

    // Parse the config using the file path
    AwsXrayAdaptiveSamplingConfig config =
        AwsApplicationSignalsCustomizerProvider.parseConfigString(absolutePath);

    // Assert the configuration was parsed correctly
    assertThat(config).isNotNull();
    assertThat(config.getVersion()).isEqualTo(1);
    assertThat(config.getErrorCaptureLimit().getErrorTracesPerSecond()).isEqualTo(10);
  }

  @Test
  void setAdaptiveSamplingConfigFromFile_invalidYaml() throws URISyntaxException {
    // Get the resource file path
    URL resourceUrl =
        getClass().getClassLoader().getResource("adaptive-sampling-config-invalid.yaml");
    assertThat(resourceUrl).isNotNull();

    // Get the absolute file path
    File configFile = new File(resourceUrl.toURI());
    String absolutePath = configFile.getAbsolutePath();

    // Parse the config using the file path
    assertThatException()
        .isThrownBy(() -> AwsApplicationSignalsCustomizerProvider.parseConfigString(absolutePath));
  }
}
