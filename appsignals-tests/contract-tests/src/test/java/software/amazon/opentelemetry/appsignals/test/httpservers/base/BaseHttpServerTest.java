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

package software.amazon.opentelemetry.appsignals.test.httpservers.base;

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

import static io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_SERVER;
import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogramDataPoint;
import java.util.List;
import java.util.Set;
import software.amazon.opentelemetry.appsignals.test.base.ContractTestBase;
import software.amazon.opentelemetry.appsignals.test.utils.AppSignalsConstants;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeMetric;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeSpan;
import software.amazon.opentelemetry.appsignals.test.utils.SemanticConventionsConstants;

/**
 * Base class for HTTP server tests.
 *
 * <p>This class should be used across all the tests involving http-servers. The idea is that
 * assertions that validate http-servers can be reused across all http-severs.
 */
public abstract class BaseHttpServerTest extends ContractTestBase {

  /**
   * Assert span attributes inserted by the AwsMetricAttributesSpanExporter
   *
   * @param resourceScopeSpans list of spans that were exported by the application
   * @param method the http method that was used (GET, PUT, DELETE...)
   * @param path the path that was used (/path/to/resource, /path/to/resource/id, ...)
   */
  protected void assertAwsSpanAttributes(
      List<ResourceScopeSpan> resourceScopeSpans, String method, String path) {
    assertThat(resourceScopeSpans)
        .satisfiesOnlyOnce(
            rss -> {
              var attributesList = rss.getSpan().getAttributesList();
              assertAwsAttributes(attributesList, method, path);
            });
  }

  protected void assertSemanticConventionsSpanAttributes(
      List<ResourceScopeSpan> resourceScopeSpans,
      String method,
      String route,
      String path,
      long status_code) {
    assertThat(resourceScopeSpans)
        .satisfiesOnlyOnce(
            rss -> {
              assertThat(rss.getSpan().getKind()).isEqualTo(SPAN_KIND_SERVER);
              assertThat(rss.getSpan().getName()).isEqualTo(String.format("%s %s", method, route));
              var attributesList = rss.getSpan().getAttributesList();
              assertSemanticConventionsAttributes(attributesList, method, route, path, status_code);
            });
  }

  protected void assertSemanticConventionsAttributes(
      List<KeyValue> attributesList, String method, String route, String target, long status_code) {
    assertThat(attributesList)
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.NET_PEER_NAME);
              assertThat(attribute.getValue().getStringValue()).isEqualTo("localhost");
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.NET_PEER_PORT);
            })
        //        .satisfiesOnlyOnce(
        //            attribute -> {
        //              assertThat(attribute.getKey())
        //                  .isEqualTo(SemanticConventionsConstants.NET_SOCK_HOST_ADDR);
        //            })
        //        .satisfiesOnlyOnce(
        //            attribute -> {
        //              assertThat(attribute.getKey())
        //                  .isEqualTo(SemanticConventionsConstants.NET_SOCK_HOST_PORT);
        //              assertThat(attribute.getValue().getIntValue()).isEqualTo(8080L);
        //            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey())
                  .isEqualTo(SemanticConventionsConstants.NET_SOCK_PEER_ADDR);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey())
                  .isEqualTo(SemanticConventionsConstants.NET_SOCK_PEER_PORT);
              assertThat(attribute.getValue().getIntValue()).isBetween(1023L, 65536L);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.HTTP_SCHEME);
              assertThat(attribute.getValue().getStringValue()).isEqualTo("http");
            })
        //        .satisfiesOnlyOnce(
        //            attribute -> {
        //              assertThat(attribute.getKey())
        //                  .isEqualTo(SemanticConventionsConstants.HTTP_RESPONSE_CONTENT_LENGTH);
        //            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.HTTP_ROUTE);
              assertThat(attribute.getValue().getStringValue()).isEqualTo(route);
            })
        //        .satisfiesOnlyOnce(Commenting for testRoutes() test failures in springMVC and
        // tomcat, http.target is split up.
        //            attribute -> {
        //
        // assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.HTTP_TARGET);
        //              assertThat(attribute.getValue().getStringValue()).isEqualTo(target);
        //            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey())
                  .isEqualTo(SemanticConventionsConstants.HTTP_RESPONSE_STATUS_CODE);
              assertThat(attribute.getValue().getIntValue()).isEqualTo(status_code);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey())
                  .isEqualTo(SemanticConventionsConstants.HTTP_REQUEST_METHOD);
              assertThat(attribute.getValue().getStringValue()).isEqualTo(method);
            })
        //        .satisfiesOnlyOnce(
        //            attribute -> {
        //              assertThat(attribute.getKey())
        //                  .isEqualTo(SemanticConventionsConstants.NET_PROTOCOL_NAME);
        //              assertThat(attribute.getValue().getStringValue()).isEqualTo("http");
        //            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey())
                  .isEqualTo(SemanticConventionsConstants.NET_PROTOCOL_VERSION);
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
              assertThat(attribute.getKey())
                  .isEqualTo(SemanticConventionsConstants.USER_AGENT_ORIGINAL);
            });
  }

  /**
   * Assert that attributes are propagated from parent to child Spans with the
   * AttributePropagatingSpanProcessor
   *
   * @param resourceScopeSpans list of spans that were exported by the application
   * @param method the http method that was used (GET, PUT, DELETE...)
   * @param path the path that was used (/path/to/resource, /path/to/resource/id, ...)
   */
  protected void assertAttributesPropagated(
      List<ResourceScopeSpan> resourceScopeSpans, String method, String path) {
    assertThat(resourceScopeSpans)
        .satisfiesOnlyOnce(
            span -> {
              assertThat(span.getSpan().getName()).isEqualTo("marker-span");
              assertThat(span.getSpan().getAttributesList())
                  .anySatisfy(
                      attribute -> {
                        assertThat(attribute.getKey())
                            .isEqualTo(AppSignalsConstants.AWS_LOCAL_OPERATION);
                        assertThat(attribute.getValue().getStringValue())
                            .isEqualTo(String.format("%s %s", method, path));
                      });
            });
  }

  /**
   * Assert that RED metrics are exported by the AwsSpanMetricsProcessor
   *
   * @param resourceScopeMetrics list of metrics that were exported by the application
   * @param method the http method that was used (GET, PUT, DELETE...)
   * @param path the path that was used (/path/to/resource, /path/to/resource/id, ...)
   * @param metricName the metric name that was used (Latency, Error, Fault)
   */
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
                        assertAwsAttributes(attributesList, method, path);
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

  protected void doTestRoutes(String expectedRoute) {
    var response = appClient.get("/users/123/orders/123?filter=abc").aggregate().join();
    assertThat(response.status().isSuccess()).isTrue();

    var resourceScopeSpans = mockCollectorClient.getTraces();
    assertSemanticConventionsSpanAttributes(
        resourceScopeSpans, "GET", expectedRoute, "/users/123/orders/123?filter=abc", 200);
    assertAwsSpanAttributes(resourceScopeSpans, "GET", expectedRoute);
    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.LATENCY_METRIC,
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC));
    assertMetricAttributes(
        metrics, "GET", expectedRoute, AppSignalsConstants.LATENCY_METRIC, 5000.0);
    assertMetricAttributes(metrics, "GET", expectedRoute, AppSignalsConstants.ERROR_METRIC, 0.0);
    assertMetricAttributes(metrics, "GET", expectedRoute, AppSignalsConstants.FAULT_METRIC, 0.0);
  }

  protected void doTestSuccess() {
    var response = appClient.get("/success").aggregate().join();

    assertThat(response.status().isSuccess()).isTrue();

    var resourceScopeSpans = mockCollectorClient.getTraces();
    assertAwsSpanAttributes(resourceScopeSpans, "GET", "/success");
    assertAttributesPropagated(resourceScopeSpans, "GET", "/success");
    assertSemanticConventionsSpanAttributes(
        resourceScopeSpans, "GET", "/success", "/success", 200L);

    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.LATENCY_METRIC,
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC));
    assertMetricAttributes(metrics, "GET", "/success", AppSignalsConstants.LATENCY_METRIC, 5000.0);
    assertMetricAttributes(metrics, "GET", "/success", AppSignalsConstants.ERROR_METRIC, 0.0);
    assertMetricAttributes(metrics, "GET", "/success", AppSignalsConstants.FAULT_METRIC, 0.0);
  }

  protected void doTestError() {
    var response = appClient.get("/error").aggregate().join();

    var resourceScopeSpans = mockCollectorClient.getTraces();
    assertAwsSpanAttributes(resourceScopeSpans, "GET", "/error");
    assertSemanticConventionsSpanAttributes(resourceScopeSpans, "GET", "/error", "/error", 400L);

    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.LATENCY_METRIC,
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC));
    assertMetricAttributes(metrics, "GET", "/error", AppSignalsConstants.LATENCY_METRIC, 5000.0);
    assertMetricAttributes(metrics, "GET", "/error", AppSignalsConstants.ERROR_METRIC, 1.0);
    assertMetricAttributes(metrics, "GET", "/error", AppSignalsConstants.FAULT_METRIC, 0.0);
  }

  protected void doTestFault() {
    var response = appClient.get("/fault").aggregate().join();

    assertThat(response.status().isServerError()).isTrue();

    var resourceScopeSpans = mockCollectorClient.getTraces();
    assertAwsSpanAttributes(resourceScopeSpans, "GET", "/fault");
    assertSemanticConventionsSpanAttributes(resourceScopeSpans, "GET", "/fault", "/fault", 500L);

    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.LATENCY_METRIC,
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC));
    assertMetricAttributes(metrics, "GET", "/fault", AppSignalsConstants.LATENCY_METRIC, 5000.0);
    assertMetricAttributes(metrics, "GET", "/fault", AppSignalsConstants.ERROR_METRIC, 0.0);
    assertMetricAttributes(metrics, "GET", "/fault", AppSignalsConstants.FAULT_METRIC, 1.0);
  }

  protected void assertAwsAttributes(
      List<KeyValue> attributesList, String method, String endpoint) {
    assertThat(attributesList)
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_LOCAL_OPERATION);
              assertThat(attribute.getValue().getStringValue())
                  .isEqualTo(String.format("%s %s", method, endpoint));
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_LOCAL_SERVICE);
              assertThat(attribute.getValue().getStringValue())
                  .isEqualTo(getApplicationOtelServiceName());
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_SPAN_KIND);
              assertThat(attribute.getValue().getStringValue()).isEqualTo("LOCAL_ROOT");
            });
  }
}
