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

import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class AwsApplicationSignalsCustomizerTest {

  private AwsApplicationSignalsCustomizerProvider provider;

  @Mock private ConfigProperties configProps;
  private AutoConfigurationCustomizer customizer;
  @Mock OtlpHttpLogRecordExporter httpLogRecordExporter;

  @BeforeEach
  void setUp() {
    provider = new AwsApplicationSignalsCustomizerProvider();
  }

  //  @Test
  //  void testShouldEnableSigV4ForLogsIfConfigIsCorrect() {
  //    new AutoConfiguredOpenTelemetrySdk().
  //      when(customizer.addLoggerProviderCustomizer(any())).then(invocation -> provider);
  //      provider.customize(customizer);
  //  }
}
