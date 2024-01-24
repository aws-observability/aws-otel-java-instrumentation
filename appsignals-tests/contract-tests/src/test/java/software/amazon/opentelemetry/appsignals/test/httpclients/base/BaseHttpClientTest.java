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

package software.amazon.opentelemetry.appsignals.test.httpclients.base;

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

import static io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_CLIENT;
import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogramDataPoint;
import java.util.List;
import java.util.Map;
import java.util.Set;
import software.amazon.opentelemetry.appsignals.test.base.ContractTestBase;
import software.amazon.opentelemetry.appsignals.test.utils.AppSignalsConstants;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeMetric;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeSpan;
import software.amazon.opentelemetry.appsignals.test.utils.SemanticConventionsConstants;

public abstract class BaseHttpClientTest extends ContractTestBase {

  @Override
  protected List<String> getApplicationNetworkAliases() {
    // This will be the target hostname of the clients making http requests in the application
    // image, so that they don't use localhost.
    return List.of("backend");
  }

  @Override
  protected Map<String, String> getApplicationExtraEnvironmentVariables() {
    return Map.of("OTEL_INSTRUMENTATION_COMMON_PEER_SERVICE_MAPPING", "backend=backend:8080");
  }

  protected void assertAwsSpanAttributes(
      List<ResourceScopeSpan> resourceScopeSpans, String method, String path) {
    assertThat(resourceScopeSpans)
        .satisfiesOnlyOnce(
            rss -> {
              assertThat(rss.getSpan().getKind()).isEqualTo(SPAN_KIND_CLIENT);
              var attributesList = rss.getSpan().getAttributesList();
              assertAwsAttributes(attributesList, method, path);
            });
  }

  protected void assertSemanticConventionsSpanAttributes(
      List<ResourceScopeSpan> resourceScopeSpans, String method, String path, long status_code) {
    assertThat(resourceScopeSpans)
        .satisfiesOnlyOnce(
            rss -> {
              assertThat(rss.getSpan().getKind()).isEqualTo(SPAN_KIND_CLIENT);
              assertThat(rss.getSpan().getName()).isEqualTo(method);
              var attributesList = rss.getSpan().getAttributesList();
              assertSemanticConventionsAttributes(attributesList, method, path, status_code);
            });
  }

  protected void assertAwsAttributes(
      List<KeyValue> attributesList, String method, String endpoint) {
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
              assertThat(attribute.getValue().getStringValue()).isEqualTo("backend:8080");
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_REMOTE_OPERATION);
              assertThat(attribute.getValue().getStringValue())
                  .isEqualTo(String.format("%s /%s", method, "backend"));
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_SPAN_KIND);
              assertThat(attribute.getValue().getStringValue()).isEqualTo("CLIENT");
            });
  }

  protected void assertSemanticConventionsAttributes(
      List<KeyValue> attributesList, String method, String endpoint, long status_code) {
    assertThat(attributesList)
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.NET_PEER_NAME);
              assertThat(attribute.getValue().getStringValue()).isEqualTo("backend");
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.NET_PEER_PORT);
              assertThat(attribute.getValue().getIntValue()).isEqualTo(8080L);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey())
                  .isEqualTo(SemanticConventionsConstants.HTTP_RESPONSE_STATUS_CODE);
              assertThat(attribute.getValue().getIntValue()).isEqualTo(status_code);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.URL_FULL);
              assertThat(attribute.getValue().getStringValue())
                  .isEqualTo(String.format("%s/%s", "http://backend:8080/backend", endpoint));
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey())
                  .isEqualTo(SemanticConventionsConstants.HTTP_REQUEST_METHOD);
              assertThat(attribute.getValue().getStringValue()).isEqualTo(method);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey())
                  .isEqualTo(SemanticConventionsConstants.NET_PROTOCOL_NAME);
              assertThat(attribute.getValue().getStringValue()).isEqualTo("http");
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey())
                  .isEqualTo(SemanticConventionsConstants.NET_PROTOCOL_VERSION);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.PEER_SERVICE);
              assertThat(attribute.getValue().getStringValue()).isEqualTo("backend:8080");
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.THREAD_ID);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.THREAD_NAME);
            });
  }

  protected void assertMetricAttributes(
      List<ResourceScopeMetric> resourceScopeMetrics,
      String method,
      String path,
      String metricName,
      Double expectedSum) {
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
                                      .isEqualTo("backend:8080");
                                })
                            .satisfiesOnlyOnce(
                                attribute -> {
                                  assertThat(attribute.getKey())
                                      .isEqualTo(AppSignalsConstants.AWS_REMOTE_OPERATION);
                                  assertThat(attribute.getValue().getStringValue())
                                      .isEqualTo(String.format("%s /%s", method, "backend"));
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

  protected void doTestSuccess() {
    var path = "success";
    var method = "GET";
    long status_code = 200;
    var response = appClient.get(path).aggregate().join();

    assertThat(response.status().isSuccess()).isTrue();

    var resourceScopeSpans = mockCollectorClient.getTraces();
    assertAwsSpanAttributes(resourceScopeSpans, method, path);
    assertSemanticConventionsSpanAttributes(resourceScopeSpans, method, path, status_code);

    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.LATENCY_METRIC,
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC));
    assertMetricAttributes(metrics, method, path, AppSignalsConstants.LATENCY_METRIC, 5000.0);
    assertMetricAttributes(metrics, method, path, AppSignalsConstants.ERROR_METRIC, 0.0);
    assertMetricAttributes(metrics, method, path, AppSignalsConstants.FAULT_METRIC, 0.0);
  }

  protected void doTestError() {
    var path = "error";
    var method = "GET";
    long status_code = 400;
    var response = appClient.get(path).aggregate().join();

    var resourceScopeSpans = mockCollectorClient.getTraces();
    assertAwsSpanAttributes(resourceScopeSpans, method, path);
    assertSemanticConventionsSpanAttributes(resourceScopeSpans, method, path, status_code);

    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.LATENCY_METRIC,
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC));
    assertMetricAttributes(metrics, method, path, AppSignalsConstants.LATENCY_METRIC, 5000.0);
    assertMetricAttributes(metrics, method, path, AppSignalsConstants.ERROR_METRIC, 1.0);
    assertMetricAttributes(metrics, method, path, AppSignalsConstants.FAULT_METRIC, 0.0);
  }

  protected void doTestFault() {
    var path = "fault";
    var method = "GET";
    long status_code = 500;
    var response = appClient.get(path).aggregate().join();

    assertThat(response.status().isServerError()).isTrue();

    var resourceScopeSpans = mockCollectorClient.getTraces();
    assertAwsSpanAttributes(resourceScopeSpans, method, path);
    assertSemanticConventionsSpanAttributes(resourceScopeSpans, method, path, status_code);

    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.LATENCY_METRIC,
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC));
    assertMetricAttributes(metrics, method, path, AppSignalsConstants.LATENCY_METRIC, 5000.0);
    assertMetricAttributes(metrics, method, path, AppSignalsConstants.ERROR_METRIC, 0.0);
    assertMetricAttributes(metrics, method, path, AppSignalsConstants.FAULT_METRIC, 1.0);
  }

  protected void doTestSuccessPost() {
    var path = "success/postmethod";
    var method = "POST";
    long status_code = 200;

    var response = appClient.post(path, "body=mock").aggregate().join();

    assertThat(response.status().isSuccess()).isTrue();

    var resourceScopeSpans = mockCollectorClient.getTraces();
    assertAwsSpanAttributes(resourceScopeSpans, method, path);
    assertSemanticConventionsSpanAttributes(resourceScopeSpans, method, path, status_code);

    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.LATENCY_METRIC,
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC));
    assertMetricAttributes(metrics, method, path, AppSignalsConstants.LATENCY_METRIC, 5000.0);
    assertMetricAttributes(metrics, method, path, AppSignalsConstants.ERROR_METRIC, 0.0);
    assertMetricAttributes(metrics, method, path, AppSignalsConstants.FAULT_METRIC, 0.0);
  }

  protected void doTestErrorPost() {
    var path = "error/postmethod";
    var method = "POST";
    long status_code = 400;

    var response = appClient.post(path, "body=mock").aggregate().join();

    var resourceScopeSpans = mockCollectorClient.getTraces();
    assertAwsSpanAttributes(resourceScopeSpans, method, path);
    assertSemanticConventionsSpanAttributes(resourceScopeSpans, method, path, status_code);

    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.LATENCY_METRIC,
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC));
    assertMetricAttributes(metrics, method, path, AppSignalsConstants.LATENCY_METRIC, 5000.0);
    assertMetricAttributes(metrics, method, path, AppSignalsConstants.ERROR_METRIC, 1.0);
    assertMetricAttributes(metrics, method, path, AppSignalsConstants.FAULT_METRIC, 0.0);
  }

  protected void doTestFaultPost() {
    var path = "fault/postmethod";
    var method = "POST";
    long status_code = 500;

    var response = appClient.post(path, "body=mock").aggregate().join();

    assertThat(response.status().isServerError()).isTrue();

    var resourceScopeSpans = mockCollectorClient.getTraces();
    assertAwsSpanAttributes(resourceScopeSpans, method, path);
    assertSemanticConventionsSpanAttributes(resourceScopeSpans, method, path, status_code);

    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.LATENCY_METRIC,
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC));
    assertMetricAttributes(metrics, method, path, AppSignalsConstants.LATENCY_METRIC, 5000.0);
    assertMetricAttributes(metrics, method, path, AppSignalsConstants.ERROR_METRIC, 0.0);
    assertMetricAttributes(metrics, method, path, AppSignalsConstants.FAULT_METRIC, 1.0);
  }
}
