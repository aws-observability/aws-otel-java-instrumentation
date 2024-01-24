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

package software.amazon.opentelemetry.appsignals.test.grpc.client;

import static io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_CLIENT;
import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogramDataPoint;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.lifecycle.Startable;
import software.amazon.opentelemetry.appsignals.test.base.ContractTestBase;
import software.amazon.opentelemetry.appsignals.test.utils.AppSignalsConstants;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeMetric;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeSpan;
import software.amazon.opentelemetry.appsignals.test.utils.SemanticConventionsConstants;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class GrpcClientTest extends ContractTestBase {

  private GenericContainer<?> grpcServer;

  private static final String GRPC_SERVICE_NAME = "echo.Echoer";

  @Override
  protected Map<String, String> getApplicationExtraEnvironmentVariables() {
    return Map.of("GRPC_CLIENT_TARGET", "server:50051");
  }

  @Override
  protected List<Startable> getApplicationDependsOnContainers() {
    grpcServer =
        new GenericContainer<>("grpc-server")
            .withNetwork(network)
            .withNetworkAliases("server")
            .waitingFor(Wait.forLogMessage(".*Server started.*", 1));
    return List.of(grpcServer);
  }

  @BeforeAll
  public void setup() {
    grpcServer.start();
  }

  @AfterAll
  public void tearDown() {
    grpcServer.stop();
  }

  @Test
  public void testSuccess() {
    var path = "success";
    var method = "GET";
    long status_code = 0;
    var otelStatusCode = "STATUS_CODE_UNSET";
    var grpcMethod = "EchoSuccess";
    var response = appClient.get(path).aggregate().join();
    assertThat(response.status().isSuccess()).isTrue();

    var traces = mockCollectorClient.getTraces();
    assertAwsSpanAttributes(traces, method, path, grpcMethod);
    assertSemanticConventionsSpanAttributes(traces, otelStatusCode, status_code, grpcMethod);

    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.LATENCY_METRIC,
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC));
    assertMetricAttributes(
        metrics, method, path, AppSignalsConstants.LATENCY_METRIC, 5000.0, grpcMethod);
    assertMetricAttributes(
        metrics, method, path, AppSignalsConstants.ERROR_METRIC, 0.0, grpcMethod);
    assertMetricAttributes(
        metrics, method, path, AppSignalsConstants.FAULT_METRIC, 0.0, grpcMethod);
  }

  @Test
  public void testError() {
    var path = "error";
    var method = "GET";
    long status_code = 13;
    var otelStatusCode = "STATUS_CODE_ERROR";
    var grpcMethod = "EchoError";
    var response = appClient.get(path).aggregate().join();
    assertThat(response.status().isClientError()).isTrue();

    var traces = mockCollectorClient.getTraces();
    assertAwsSpanAttributes(traces, method, path, grpcMethod);
    assertSemanticConventionsSpanAttributes(traces, otelStatusCode, status_code, grpcMethod);

    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.LATENCY_METRIC,
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC));
    assertMetricAttributes(
        metrics, method, path, AppSignalsConstants.LATENCY_METRIC, 5000.0, grpcMethod);
    assertMetricAttributes(
        metrics, method, path, AppSignalsConstants.ERROR_METRIC, 0.0, grpcMethod);
    assertMetricAttributes(
        metrics, method, path, AppSignalsConstants.FAULT_METRIC, 1.0, grpcMethod);
  }

  @Test
  public void testFault() {
    var path = "fault";
    var method = "GET";
    long status_code = 14;
    var otelStatusCode = "STATUS_CODE_ERROR";
    var grpcMethod = "EchoFault";
    var response = appClient.get(path).aggregate().join();
    assertThat(response.status().isServerError()).isTrue();

    var traces = mockCollectorClient.getTraces();
    assertAwsSpanAttributes(traces, method, path, grpcMethod);
    assertSemanticConventionsSpanAttributes(traces, otelStatusCode, status_code, grpcMethod);

    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.LATENCY_METRIC,
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC));
    assertMetricAttributes(
        metrics, method, path, AppSignalsConstants.LATENCY_METRIC, 5000.0, grpcMethod);
    assertMetricAttributes(
        metrics, method, path, AppSignalsConstants.ERROR_METRIC, 0.0, grpcMethod);
    assertMetricAttributes(
        metrics, method, path, AppSignalsConstants.FAULT_METRIC, 1.0, grpcMethod);
  }

  @Override
  protected String getApplicationImageName() {
    return "grpc-client";
  }

  @Override
  protected String getApplicationWaitPattern() {
    return ".*Routes ready.*";
  }

  protected void assertAwsSpanAttributes(
      List<ResourceScopeSpan> resourceScopeSpans, String method, String path, String grpcMethod) {
    assertThat(resourceScopeSpans)
        .satisfiesOnlyOnce(
            rss -> {
              assertThat(rss.getSpan().getKind()).isEqualTo(SPAN_KIND_CLIENT);
              var attributesList = rss.getSpan().getAttributesList();
              assertAwsAttributes(attributesList, method, path, grpcMethod);
            });
  }

  protected void assertAwsAttributes(
      List<KeyValue> attributesList, String method, String endpoint, String grpcMethod) {
    assertThat(attributesList)
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_LOCAL_OPERATION);
              assertThat(attribute.getValue().getStringValue())
                  .isEqualTo(String.format("%s /%s", method, endpoint));
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_LOCAL_SERVICE);
              assertThat(attribute.getValue().getStringValue())
                  .isEqualTo(getApplicationOtelServiceName());
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_REMOTE_SERVICE);
              assertThat(attribute.getValue().getStringValue()).isEqualTo(GRPC_SERVICE_NAME);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_REMOTE_OPERATION);
              assertThat(attribute.getValue().getStringValue()).isEqualTo(grpcMethod);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_SPAN_KIND);
              assertThat(attribute.getValue().getStringValue()).isEqualTo("CLIENT");
            });
  }

  protected void assertSemanticConventionsSpanAttributes(
      List<ResourceScopeSpan> resourceScopeSpans,
      String otelStatusCode,
      long status_code,
      String grpcMethod) {
    assertThat(resourceScopeSpans)
        .satisfiesOnlyOnce(
            rss -> {
              assertThat(rss.getSpan().getKind()).isEqualTo(SPAN_KIND_CLIENT);
              assertThat(rss.getSpan().getName())
                  .isEqualTo(String.format("%s/%s", GRPC_SERVICE_NAME, grpcMethod));
              assertThat(rss.getSpan().getStatus().getCode().equals(otelStatusCode));
              var attributesList = rss.getSpan().getAttributesList();
              assertSemanticConventionsAttributes(attributesList, status_code, grpcMethod);
            });
  }

  protected void assertSemanticConventionsAttributes(
      List<KeyValue> attributesList, long status_code, String grpcMethod) {
    assertThat(attributesList)
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.NET_PEER_NAME);
              assertThat(attribute.getValue().getStringValue()).isEqualTo("server");
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.NET_PEER_PORT);
              assertThat(attribute.getValue().getIntValue()).isEqualTo(50051L);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey())
                  .isEqualTo(SemanticConventionsConstants.RPC_GRPC_STATUS_CODE);
              assertThat(attribute.getValue().getIntValue()).isEqualTo(status_code);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.RPC_METHOD);
              assertThat(attribute.getValue().getStringValue()).isEqualTo(grpcMethod);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.THREAD_ID);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.THREAD_NAME);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.RPC_SYSTEM);
              assertThat(attribute.getValue().getStringValue()).isEqualTo("grpc");
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.RPC_SERVICE);
              assertThat(attribute.getValue().getStringValue()).isEqualTo(GRPC_SERVICE_NAME);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey())
                  .isEqualTo(SemanticConventionsConstants.NET_SOCK_PEER_ADDR);
            });
  }

  protected void assertMetricAttributes(
      List<ResourceScopeMetric> resourceScopeMetrics,
      String method,
      String path,
      String metricName,
      Double expectedSum,
      String grpcMethod) {
    assertThat(resourceScopeMetrics)
        .anySatisfy(
            metric -> {
              assertThat(metric.getMetric().getName()).isEqualTo(metricName);
              List<ExponentialHistogramDataPoint> dpList =
                  metric.getMetric().getExponentialHistogram().getDataPointsList();
              assertThat(dpList)
                  .satisfiesOnlyOnce(
                      dp -> {
                        List<KeyValue> attributesList = dp.getAttributesList();
                        assertThat(attributesList).isNotNull();
                        assertThat(attributesList)
                            .satisfiesOnlyOnce(
                                attribute -> {
                                  assertThat(attribute.getKey())
                                      .isEqualTo(AppSignalsConstants.AWS_SPAN_KIND);
                                  assertThat(attribute.getValue().getStringValue())
                                      .isEqualTo("CLIENT");
                                })
                            .satisfiesOnlyOnce(
                                attribute -> {
                                  assertThat(attribute.getKey())
                                      .isEqualTo(AppSignalsConstants.AWS_LOCAL_OPERATION);
                                  assertThat(attribute.getValue().getStringValue())
                                      .isEqualTo(String.format("%s /%s", method, path));
                                })
                            .satisfiesOnlyOnce(
                                attribute -> {
                                  assertThat(attribute.getKey())
                                      .isEqualTo(AppSignalsConstants.AWS_LOCAL_SERVICE);
                                  assertThat(attribute.getValue().getStringValue())
                                      .isEqualTo(getApplicationOtelServiceName());
                                })
                            .satisfiesOnlyOnce(
                                attribute -> {
                                  assertThat(attribute.getKey())
                                      .isEqualTo(AppSignalsConstants.AWS_REMOTE_SERVICE);
                                  assertThat(attribute.getValue().getStringValue())
                                      .isEqualTo(GRPC_SERVICE_NAME);
                                })
                            .satisfiesOnlyOnce(
                                attribute -> {
                                  assertThat(attribute.getKey())
                                      .isEqualTo(AppSignalsConstants.AWS_REMOTE_OPERATION);
                                  assertThat(attribute.getValue().getStringValue())
                                      .isEqualTo(grpcMethod);
                                });

                        if (expectedSum != null) {
                          double actualSum = dp.getSum();
                          switch (metricName) {
                            case AppSignalsConstants.LATENCY_METRIC:
                              assertThat(actualSum).isStrictlyBetween(0.0, expectedSum);
                              break;
                            default:
                              assertThat(actualSum).isEqualTo(expectedSum);
                          }
                        }
                      });
            });
  }
}
