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

package software.amazon.opentelemetry.javaagent.providers.exporter.aws.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class NoOpEmfExporterTest {
  @ParameterizedTest
  @EnumSource(InstrumentType.class)
  void testGetAggregationTemporality(InstrumentType instrumentType) {
    assertThat(NoOpEmfExporter.INSTANCE.getAggregationTemporality(instrumentType))
        .isEqualTo(AggregationTemporality.DELTA);
  }

  @ParameterizedTest
  @EnumSource(InstrumentType.class)
  void testGetDefaultAggregation(InstrumentType instrumentType) {
    assertThat(NoOpEmfExporter.INSTANCE.getDefaultAggregation(instrumentType))
        .isEqualTo(Aggregation.drop());
  }

  @Test
  void testExport() {
    MetricData metric = mock(MetricData.class);

    assertThat(NoOpEmfExporter.INSTANCE.export(Collections.singletonList(metric)).isSuccess())
        .isTrue();
    verifyNoInteractions(metric);
  }

  @Test
  void testFlush() {
    assertThat(NoOpEmfExporter.INSTANCE.flush().isSuccess()).isTrue();
  }

  @Test
  void testShutdown() {
    assertThat(NoOpEmfExporter.INSTANCE.shutdown().isSuccess()).isTrue();
  }
}
