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

package software.amazon.opentelemetry.cloudwatch.spanmetrics.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises the plain-autoconfigure bind path (the .internal AutoConfigureListener hook). */
class SpanMetricsCustomizerProviderBindingTest {

  @BeforeEach
  @AfterEach
  void clear() {
    OpenTelemetryHolder.reset();
  }

  @Test
  void afterAutoConfigureBindsTheSdk() {
    OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().build();
    new SpanMetricsCustomizerProvider().afterAutoConfigure(sdk);
    assertThat(OpenTelemetryHolder.get()).isSameAs(sdk);
  }
}
