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
import io.opentelemetry.proto.profiles.v1development.Profile;
import io.opentelemetry.proto.profiles.v1development.ProfilesDictionary;
import io.opentelemetry.proto.profiles.v1development.ResourceProfiles;
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
 * End-to-end contract test for the profiler's <b>OTLP/gRPC</b> transport ({@code
 * OTEL_EXPORTER_OTLP_PROTOCOL=grpc}). The other profiler tests use HTTP/protobuf; this drives the
 * {@code OtlpGrpcProfilesExporter} — a unary {@code ProfilesService/Export} over HTTP/2 — against
 * the mock collector's gRPC handler, with gzip on so the compressed-frame de-gzip path is
 * exercised. Asserts that profiles arrive over gRPC (proving the transport) and carry a wall
 * Profile with samples.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AwsProfilerGrpcContractTest extends ServiceEventsContractTestBase {

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
    // gRPC transport: unary ProfilesService/Export over HTTP/2. Endpoint is host:port with no path
    // (the exporter appends the fixed RPC path). gzip exercises the compressed gRPC frame.
    env.put("OTEL_EXPORTER_OTLP_PROTOCOL", "grpc");
    env.put("OTEL_AWS_PROFILER_ENDPOINT", "http://mock-collector:4317");
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
            "profiler-grpc-contract-traffic");
    traffic.setDaemon(true);
    traffic.start();

    try {
      applicationLogger.info("=== Waiting for gRPC OTLP profiles export (JFR rotation ~60s) ===");
      Instant deadline = Instant.now().plus(PROFILE_WAIT);
      while (Instant.now().isBefore(deadline)) {
        try {
          List<ExportProfilesServiceRequest> captured = mockCollectorClient.getProfiles();
          if (!captured.isEmpty()) {
            profiles = captured;
            break;
          }
        } catch (RuntimeException e) {
          applicationLogger.warn("Waiting for gRPC OTLP profiles export: " + e.getMessage());
        }
      }
    } finally {
      keepDriving.set(false);
      traffic.join(Duration.ofSeconds(10).toMillis());
    }

    assertThat(profiles)
        .as("mock collector must capture at least one ExportProfilesServiceRequest over gRPC")
        .isNotEmpty();
  }

  @Test
  @Order(1)
  void testGrpcDeliveredWallProfileWithSamples() {
    boolean hasWallWithSamples = false;
    for (ExportProfilesServiceRequest request : profiles) {
      ProfilesDictionary dict = request.getDictionary();
      for (Profile profile : allProfiles(request)) {
        if ("wall".equals(dict.getStringTable(profile.getSampleType().getTypeStrindex()))
            && profile.getSamplesCount() > 0) {
          hasWallWithSamples = true;
        }
      }
    }
    assertThat(hasWallWithSamples)
        .as("a wall Profile with samples must arrive over the gRPC transport")
        .isTrue();
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
