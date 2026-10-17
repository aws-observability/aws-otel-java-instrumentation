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

package software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;

class OtlpAwsConfigUtilTest {
  private DefaultAwsRegionProviderChain regionProviderChain;
  private MockedStatic<DefaultAwsRegionProviderChain> regionProviderFactory;

  @BeforeEach
  void setup() {
    DefaultAwsRegionProviderChain.Builder builder =
        mock(DefaultAwsRegionProviderChain.Builder.class);
    regionProviderChain = mock(DefaultAwsRegionProviderChain.class);
    when(builder.build()).thenReturn(regionProviderChain);
    when(regionProviderChain.getRegion()).thenReturn(Region.US_WEST_1);
    regionProviderFactory = mockStatic(DefaultAwsRegionProviderChain.class);
    regionProviderFactory.when(DefaultAwsRegionProviderChain::builder).thenReturn(builder);
  }

  @AfterEach
  void cleanup() {
    regionProviderFactory.close();
  }

  @ParameterizedTest
  @MethodSource("awsRegionConfigs")
  void testGetAwsRegionShouldReturnExpectedRegion(
      Map<String, String> properties, String expectedRegion) {
    ConfigProperties config = DefaultConfigProperties.createFromMap(properties);
    assertThat(
            OtlpAwsConfigUtil.getAwsRegion(config, config.getString("otel.exporter.otlp.endpoint")))
        .isEqualTo(expectedRegion);
  }

  @ParameterizedTest
  @MethodSource("unresolvedAwsRegionConfigs")
  void testGetAwsRegionShouldThrowWhenRegionCannotBeResolved(Map<String, String> properties) {
    ConfigProperties config = DefaultConfigProperties.createFromMap(properties);
    when(regionProviderChain.getRegion())
        .thenThrow(SdkClientException.builder().message("no region").build());
    assertThatThrownBy(
            () ->
                OtlpAwsConfigUtil.getAwsRegion(
                    config, config.getString("otel.exporter.otlp.endpoint")))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("AWS SDK region provider chain");
  }

  @ParameterizedTest
  @MethodSource("awsServiceConfigs")
  void testGetAwsServiceShouldReturnExpectedService(
      String dataType, Map<String, String> properties, String expectedAwsService) {
    ConfigProperties config = DefaultConfigProperties.createFromMap(properties);
    assertThat(OtlpAwsConfigUtil.getAwsService(dataType, config)).isEqualTo(expectedAwsService);
  }

  @ParameterizedTest
  @MethodSource("compressionConfigs")
  void testGetCompressionShouldReturnExpectedCompression(
      String dataType, Map<String, String> properties, String expectedCompression) {
    ConfigProperties config = DefaultConfigProperties.createFromMap(properties);
    assertThat(OtlpAwsConfigUtil.getCompression(dataType, config)).isEqualTo(expectedCompression);
  }

  @ParameterizedTest
  @MethodSource("unsupportedCompressionConfigs")
  void testGetCompressionShouldThrowWhenUnsupported(
      String dataType, Map<String, String> properties) {
    ConfigProperties config = DefaultConfigProperties.createFromMap(properties);
    assertThatThrownBy(() -> OtlpAwsConfigUtil.getCompression(dataType, config))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("compression");
  }

  private static Stream<Arguments> awsRegionConfigs() {
    return Stream.of(
        Arguments.of(
            Map.of("otel.exporter.otlp.endpoint", "https://xray.us-east-1.amazonaws.com/v1/traces"),
            "us-east-1"),
        Arguments.of(
            Map.of(
                "otel.exporter.otlp.endpoint", "https://logs.cn-north-1.amazonaws.com.cn/v1/logs"),
            "cn-north-1"),
        Arguments.of(
            Map.of(
                "otel.exporter.otlp.endpoint",
                "https://pipeline-name.us-west-2.osis.amazonaws.com/Path/TrAcEs"),
            "us-west-2"),
        Arguments.of(
            Map.of(
                "otel.exporter.otlp.endpoint",
                "https://vpce-012345.osis.eu-west-1.vpce.amazonaws.com/v1/logs"),
            "eu-west-1"),
        Arguments.of(
            Map.of(
                "otel.exporter.otlp.endpoint",
                "https://monitoring.us-gov-west-1.amazonaws.com/v1/metrics"),
            "us-gov-west-1"),
        Arguments.of(
            Map.of("otel.exporter.otlp.endpoint", "https://logs.us-east-1.api.aws/v1/logs"),
            "us-east-1"),
        Arguments.of(
            Map.of("otel.exporter.otlp.endpoint", "https://logs.us-iso-east-1.c2s.ic.gov/v1/logs"),
            "us-iso-east-1"),
        Arguments.of(
            Map.of(
                "otel.exporter.otlp.endpoint",
                "https://pipeline.us-east-1.osis.amazonaws.com/v1/traces",
                "aws.region",
                "eu-west-1",
                "aws.default.region",
                "us-west-2"),
            "eu-west-1"),
        Arguments.of(
            Map.of(
                "otel.exporter.otlp.endpoint",
                "https://pipeline.us-east-1.osis.amazonaws.com/v1/traces",
                "aws.default.region",
                "us-west-2"),
            "us-west-2"),
        Arguments.of(
            Map.of(
                "otel.exporter.otlp.endpoint",
                "https://pipeline.us-east-1.osis.amazonaws.com/v1/traces",
                "aws.region",
                "ap-southeast-2"),
            "ap-southeast-2"),
        Arguments.of(
            Map.of("otel.exporter.otlp.endpoint", "https://us-east-1.example.com/v1/traces"),
            "us-west-1"),
        Arguments.of(
            Map.of("otel.exporter.otlp.endpoint", OtlpAwsConfigUtil.DEFAULT_TRACES_ENDPOINT),
            "us-west-1"));
  }

  private static Stream<Arguments> unresolvedAwsRegionConfigs() {
    return Stream.of(
        Arguments.of(
            Map.of("otel.exporter.otlp.endpoint", OtlpAwsConfigUtil.DEFAULT_TRACES_ENDPOINT)),
        Arguments.of(
            Map.of("otel.exporter.otlp.endpoint", "https://us-east-1.example.com/v1/traces")));
  }

  private static Stream<Arguments> awsServiceConfigs() {
    List<Arguments> configurations = new ArrayList<>();
    for (Map.Entry<String, String> entry :
        Map.of("traces", "xray", "metrics", "monitoring", "logs", "logs").entrySet()) {
      String dataType = entry.getKey();
      String defaultAwsService = entry.getValue();
      String serviceKey = "aws." + dataType + ".exporter.service";
      configurations.add(Arguments.of(dataType, Map.of(), defaultAwsService));
      configurations.add(
          Arguments.of(dataType, Map.of(serviceKey, "custom-service"), "custom-service"));
      configurations.add(
          Arguments.of(
              dataType, Map.of("aws.exporter.service", "global-service"), "global-service"));
      configurations.add(
          Arguments.of(
              dataType,
              Map.of("aws.exporter.service", "global-service", serviceKey, "custom-service"),
              "global-service"));
    }
    return configurations.stream();
  }

  private static Stream<Arguments> compressionConfigs() {
    List<Arguments> configurations = new ArrayList<>();
    for (String dataType : List.of("traces", "metrics", "logs")) {
      String compressionKey = "otel.exporter.otlp." + dataType + ".compression";
      configurations.add(Arguments.of(dataType, Map.of(), "none"));
      configurations.add(
          Arguments.of(dataType, Map.of("otel.exporter.otlp.compression", "gzip"), "gzip"));
      configurations.add(Arguments.of(dataType, Map.of(compressionKey, "gzip"), "gzip"));
      configurations.add(
          Arguments.of(
              dataType,
              Map.of("otel.exporter.otlp.compression", "gzip", compressionKey, "none"),
              "none"));
    }
    return configurations.stream();
  }

  private static Stream<Arguments> unsupportedCompressionConfigs() {
    List<Arguments> configurations = new ArrayList<>();
    for (String dataType : List.of("traces", "metrics", "logs")) {
      configurations.add(
          Arguments.of(dataType, Map.of("otel.exporter.otlp.compression", "unsupported")));
      configurations.add(
          Arguments.of(
              dataType,
              Map.of(
                  "otel.exporter.otlp.compression",
                  "gzip",
                  "otel.exporter.otlp." + dataType + ".compression",
                  "unsupported")));
    }
    return configurations.stream();
  }
}
