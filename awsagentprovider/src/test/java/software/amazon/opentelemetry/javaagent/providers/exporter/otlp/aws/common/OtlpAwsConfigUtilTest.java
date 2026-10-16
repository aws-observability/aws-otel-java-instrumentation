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

import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;

class OtlpAwsConfigUtilTest {
  @ParameterizedTest
  @CsvSource({
    "https://xray.us-east-1.amazonaws.com/v1/traces,us-east-1",
    "https://logs.cn-north-1.amazonaws.com.cn/v1/logs,cn-north-1",
    "https://pipeline-name.us-west-2.osis.amazonaws.com/Path/TrAcEs,us-west-2",
    "https://vpce-012345.osis.eu-west-1.vpce.amazonaws.com/v1/logs,eu-west-1",
    "https://monitoring.us-gov-west-1.amazonaws.com/v1/metrics,us-gov-west-1",
    "https://logs.us-east-1.api.aws/v1/logs,us-east-1",
    "https://logs.us-iso-east-1.c2s.ic.gov/v1/logs,us-iso-east-1"
  })
  void testShouldDetectRegionFromAwsEndpoint(String endpoint, String expectedRegion) {
    assertThat(OtlpAwsConfigUtil.resolveRegion(null, endpoint)).isEqualTo(expectedRegion);
  }

  @Test
  void testShouldApplyRegionPrecedenceWithExplicitRegion() {
    String endpoint = "https://pipeline.us-east-1.osis.amazonaws.com/v1/traces";
    assertThat(
            OtlpAwsConfigUtil.getAwsRegion(
                DefaultConfigProperties.createFromMap(
                    Map.of("aws.region", "eu-west-1", "aws.default.region", "us-west-2")),
                endpoint))
        .isEqualTo("eu-west-1");
    assertThat(
            OtlpAwsConfigUtil.getAwsRegion(
                DefaultConfigProperties.createFromMap(Map.of("aws.default.region", "us-west-2")),
                endpoint))
        .isEqualTo("us-west-2");
    assertThat(OtlpAwsConfigUtil.resolveRegion("ap-southeast-2", endpoint))
        .isEqualTo("ap-southeast-2");
  }

  @Test
  void testOtlpSigV4ExporterShouldFailToInitializeWhenRegionCannotBeResolved() {
    DefaultAwsRegionProviderChain.Builder builder =
        mock(DefaultAwsRegionProviderChain.Builder.class);
    DefaultAwsRegionProviderChain chain = mock(DefaultAwsRegionProviderChain.class);
    when(builder.build()).thenReturn(chain);
    try (MockedStatic<DefaultAwsRegionProviderChain> factory =
        mockStatic(DefaultAwsRegionProviderChain.class)) {
      factory.when(DefaultAwsRegionProviderChain::builder).thenReturn(builder);
      when(chain.getRegion()).thenReturn(Region.EU_WEST_1);
      // Region-looking labels outside recognized AWS suffixes must not be trusted.
      assertThat(OtlpAwsConfigUtil.resolveRegion(null, "https://us-east-1.example.com/v1/traces"))
          .isEqualTo("eu-west-1");
      when(chain.getRegion()).thenThrow(SdkClientException.builder().message("no region").build());
      assertThatThrownBy(
              () -> OtlpAwsConfigUtil.resolveRegion(null, "http://localhost:4318/v1/traces"))
          .isInstanceOf(ConfigurationException.class)
          .hasMessageContaining("AWS SDK region provider chain");
    }
  }
}
