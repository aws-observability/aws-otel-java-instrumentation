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

package software.amazon.opentelemetry.appsignals.test.serviceevents.springmvc;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.proto.collector.profiles.v1development.ExportProfilesServiceRequest;
import io.opentelemetry.proto.profiles.v1development.KeyValueAndUnit;
import io.opentelemetry.proto.profiles.v1development.Link;
import io.opentelemetry.proto.profiles.v1development.Profile;
import io.opentelemetry.proto.profiles.v1development.ProfilesDictionary;
import io.opentelemetry.proto.profiles.v1development.ResourceProfiles;
import io.opentelemetry.proto.profiles.v1development.Sample;
import io.opentelemetry.proto.profiles.v1development.ScopeProfiles;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.opentelemetry.appsignals.test.serviceevents.base.ServiceEventsContractTestBase;

/**
 * End-to-end contract test for profile correlation on <b>unsampled</b> requests.
 *
 * <p>{@link AwsProfilerContractTest} covers the {@code always_on} (sampled) case where samples
 * carry both an {@code operation} attribute and a trace {@link Link}. This covers the complementary
 * case: with App Signals on (so ADOT's {@code AlwaysRecordSampler} keeps request-boundary spans
 * RECORD_ONLY) and the underlying sampler dropping every trace ({@code traceidratio} = 0), the span
 * processor still writes a {@code profiler.Span} marker with the {@code operation} but — gated on
 * {@code isSampled()} — <b>no</b> trace ids. So samples must carry {@code operation} and <b>no</b>
 * trace {@link Link}. This is the runtime counterpart of the unit {@code
 * RotationBoundaryProcessorSpanApiScanTest} UNSAMPLED case.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AwsProfilerUnsampledContractTest extends ServiceEventsContractTestBase {

  private static final Duration PROFILE_WAIT = Duration.ofSeconds(210);

  private List<ExportProfilesServiceRequest> profiles = List.of();

  @Override
  protected String getApplicationImageName() {
    return "aws-serviceevents-tests-http-server-spring-mvc";
  }

  @Override
  protected Map<String, String> getApplicationExtraEnvironmentVariables() {
    Map<String, String> env = new HashMap<>();
    env.put("OTEL_AWS_PROFILER_ENABLED", "true");
    // AlwaysRecordSampler (activated with App Signals) keeps spans RECORD_ONLY while the underlying
    // ratio sampler drops every trace -> unsampled request-boundary spans that still get a marker.
    env.put("OTEL_AWS_APPLICATION_SIGNALS_ENABLED", "true");
    env.put("OTEL_TRACES_SAMPLER", "traceidratio");
    env.put("OTEL_TRACES_SAMPLER_ARG", "0.0");
    env.put("OTEL_AWS_PROFILER_ENDPOINT", "http://mock-collector:4317/v1development/profiles");
    env.put("OTEL_AWS_PROFILER_EXPORT_COMPRESSION", "gzip");
    return env;
  }

  @Override
  protected void setUp() {}

  @Override
  protected void tearDown() {}

  @AfterAll
  void tearDownOnce() {
    if (application != null && application.isRunning()) {
      application.stop();
    }
    if (mockCollector != null && mockCollector.isRunning()) {
      mockCollector.stop();
    }
    if (network != null) {
      network.close();
    }
  }

  @Test
  @Order(0)
  void testSetupDriveTrafficAndHarvestProfiles() throws Exception {
    super.setUp();

    AtomicBoolean keepDriving = new AtomicBoolean(true);
    Thread traffic =
        new Thread(
            () -> {
              while (keepDriving.get()) {
                try {
                  sendRequest("cpu-work");
                  sendRequest("success");
                  sendRequest("moderate-work");
                } catch (RuntimeException ignored) {
                  // container shutting down / transient
                }
              }
            },
            "profiler-unsampled-contract-traffic");
    traffic.setDaemon(true);
    traffic.start();

    try {
      applicationLogger.info("=== Waiting for unsampled OTLP profiles export (JFR rotation ~60s) ===");
      Instant deadline = Instant.now().plus(PROFILE_WAIT);
      while (Instant.now().isBefore(deadline)) {
        try {
          List<ExportProfilesServiceRequest> captured = mockCollectorClient.getProfiles();
          if (!captured.isEmpty()) {
            profiles = captured;
            break;
          }
        } catch (RuntimeException e) {
          applicationLogger.warn("Waiting for unsampled OTLP profiles export: " + e.getMessage());
        }
      }
    } finally {
      keepDriving.set(false);
      traffic.join(Duration.ofSeconds(10).toMillis());
    }

    assertThat(profiles)
        .as("mock collector must capture at least one ExportProfilesServiceRequest")
        .isNotEmpty();
  }

  @Test
  @Order(1)
  void testUnsampledSamplesCarryOperationButNoTraceLink() {
    // Correlation still attaches the operation on request threads...
    assertThat(anyOperationValue())
        .as("unsampled request samples must still carry the 'operation' attribute")
        .isNotNull();

    // ...but because the trace was dropped (isSampled() == false), no sample carries a trace Link.
    boolean anyLink = false;
    for (ExportProfilesServiceRequest request : profiles) {
      ProfilesDictionary dict = request.getDictionary();
      for (Profile profile : allProfiles(request)) {
        for (Sample sample : profile.getSamplesList()) {
          if (sample.getLinkIndex() != 0) {
            Link link = dict.getLinkTable(sample.getLinkIndex());
            if (!link.getTraceId().isEmpty()) {
              anyLink = true;
            }
          }
        }
      }
    }
    assertThat(anyLink)
        .as("unsampled samples must NOT carry a trace Link (trace dropped before export)")
        .isFalse();
  }

  private String anyOperationValue() {
    for (ExportProfilesServiceRequest request : profiles) {
      ProfilesDictionary dict = request.getDictionary();
      for (Profile profile : allProfiles(request)) {
        for (Sample sample : profile.getSamplesList()) {
          for (int attrIdx : sample.getAttributeIndicesList()) {
            KeyValueAndUnit attr = dict.getAttributeTable(attrIdx);
            if ("operation".equals(dict.getStringTable(attr.getKeyStrindex()))) {
              String value = attr.getValue().getStringValue();
              if (value != null && !value.isEmpty()) {
                return value;
              }
            }
          }
        }
      }
    }
    return null;
  }

  private static List<Profile> allProfiles(ExportProfilesServiceRequest request) {
    List<Profile> out = new ArrayList<>();
    for (ResourceProfiles rp : request.getResourceProfilesList()) {
      for (ScopeProfiles sp : rp.getScopeProfilesList()) {
        out.addAll(sp.getProfilesList());
      }
    }
    return out;
  }
}
