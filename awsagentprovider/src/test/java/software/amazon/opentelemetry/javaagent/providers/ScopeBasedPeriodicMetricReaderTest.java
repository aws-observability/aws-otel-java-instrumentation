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

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.CollectionRegistration;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableGaugeData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableMetricData;
import io.opentelemetry.sdk.resources.Resource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScopeBasedPeriodicMetricReaderTest {
  @Mock private MetricExporter metricExporter;
  @Mock private CollectionRegistration collectionRegistration;

  private ScopeBasedPeriodicMetricReader reader;

  @BeforeEach
  public void setup() {
    Set<String> scopeNames = new HashSet<>();
    scopeNames.add("io.test.retained");
    reader =
        ScopeBasedPeriodicMetricReader.create(metricExporter, scopeNames)
            .setInterval(60, TimeUnit.SECONDS)
            .build();
    reader.register(collectionRegistration);
  }

  @Test
  public void testScopeBasedMetricFilter() {
    List<MetricData> metricDataList = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      metricDataList.add(getMetricData("io.test.retained"));
    }
    for (int i = 0; i < 3; i++) {
      metricDataList.add(getMetricData("io.test.dropped"));
    }

    Mockito.when(metricExporter.export(Mockito.anyList()))
        .thenReturn(CompletableResultCode.ofSuccess());
    Mockito.when(collectionRegistration.collectAllMetrics()).thenReturn(metricDataList);

    CompletableResultCode result = reader.forceFlush();

    Mockito.verify(metricExporter).export(Mockito.argThat(list -> list.size() == 5));
    assertTrue(result.isSuccess());
  }

  @Test
  public void testEmptyMetrics() {
    Mockito.when(collectionRegistration.collectAllMetrics()).thenReturn(new ArrayList<>());

    CompletableResultCode result = reader.forceFlush();

    Mockito.verify(metricExporter, Mockito.never()).export(Mockito.anyList());
    assertTrue(result.isSuccess());
  }

  @Test
  public void testShutdown() {
    List<MetricData> metricDataList = new ArrayList<>();
    for (int i = 0; i < 1; i++) {
      metricDataList.add(getMetricData("io.test.retained"));
    }

    Mockito.when(metricExporter.export(Mockito.anyList()))
        .thenReturn(CompletableResultCode.ofSuccess());
    Mockito.when(metricExporter.shutdown()).thenReturn(CompletableResultCode.ofSuccess());
    Mockito.when(collectionRegistration.collectAllMetrics()).thenReturn(metricDataList);

    CompletableResultCode result = reader.shutdown();

    Mockito.verify(metricExporter).export(Mockito.argThat(list -> list.size() == 1));
    assertTrue(result.isSuccess());
  }

  private static MetricData getMetricData(String instrumentationScope) {
    return ImmutableMetricData.createDoubleGauge(
        Resource.empty(),
        InstrumentationScopeInfo.create(instrumentationScope),
        "test",
        "",
        "1",
        ImmutableGaugeData.empty());
  }
}
