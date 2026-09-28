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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BaseOtlpAwsExporterTest {

  /** The SigV4 signing region is taken from the endpoint host, including China partition hosts. */
  @ParameterizedTest
  @CsvSource({
    "https://xray.us-east-1.amazonaws.com/v1/traces, us-east-1",
    "https://xray.cn-north-1.amazonaws.com.cn/v1/traces, cn-north-1",
    "https://logs.cn-northwest-1.amazonaws.com.cn/v1/logs, cn-northwest-1",
    "https://monitoring.cn-north-1.amazonaws.com.cn/v1/metrics, cn-north-1"
  })
  void testSigningRegionIsExtractedFromEndpoint(String endpoint, String expectedRegion) {
    BaseOtlpAwsExporter exporter =
        new BaseOtlpAwsExporter(endpoint, CompressionMethod.NONE) {
          @Override
          public String serviceName() {
            return "xray";
          }
        };

    assertEquals(expectedRegion, exporter.awsRegion);
  }
}
