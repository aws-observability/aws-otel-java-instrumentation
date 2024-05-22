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

package software.amazon.opentelemetry.appsignals.test.jdbc;

import static io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_CLIENT;
import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogramDataPoint;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.opentelemetry.appsignals.test.base.ContractTestBase;
import software.amazon.opentelemetry.appsignals.test.utils.AppSignalsConstants;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeMetric;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeSpan;
import software.amazon.opentelemetry.appsignals.test.utils.SemanticConventionsConstants;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class JdbcH2Test extends ContractTestBase {

  private static final String DB_SYSTEM = "h2";
  private static final String DB_NAME = "testdb";
  private static final String DB_USER = "sa";
  private static final String DB_PASSWORD = "password";
  private static final String DB_OPERATION = "SELECT";

  @Test
  public void testSuccess() {
    var path = "success";
    var method = "GET";
    var otelStatusCode = "STATUS_CODE_UNSET";
    var dbSqlTable = "employee";
    var response = appClient.get(path).aggregate().join();
    assertThat(response.status().isSuccess()).isTrue();

    var traces = mockCollectorClient.getTraces();
    assertAwsSpanAttributes(traces, method, path);
    assertSemanticConventionsSpanAttributes(traces, otelStatusCode, dbSqlTable);

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

  @Test
  public void testFault() {
    var path = "fault";
    var method = "GET";
    var otelStatusCode = "STATUS_CODE_ERROR";
    var dbSqlTable = "userrr";
    var response = appClient.get(path).aggregate().join();
    assertThat(response.status().isServerError()).isTrue();

    var traces = mockCollectorClient.getTraces();
    assertAwsSpanAttributes(traces, method, path);
    assertSemanticConventionsSpanAttributes(traces, otelStatusCode, dbSqlTable);

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

  @Override
  protected String getApplicationImageName() {
    return "aws-appsignals-tests-jdbc-app";
  }

  @Override
  protected String getApplicationWaitPattern() {
    return ".*Application Ready.*";
  }

  @Override
  protected Map<String, String> getApplicationExtraEnvironmentVariables() {
    return Map.of(
        "DB_URL",
        String.format("jdbc:h2:mem:%s", DB_NAME),
        "DB_DRIVER",
        "org.h2.Driver",
        "DB_USERNAME",
        DB_USER,
        "DB_PASSWORD",
        DB_PASSWORD,
        "DB_PLATFORM",
        "org.hibernate.dialect.H2Dialect");
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
              assertThat(attribute.getValue().getStringValue()).isEqualTo(DB_SYSTEM);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_REMOTE_OPERATION);
              assertThat(attribute.getValue().getStringValue()).isEqualTo(DB_OPERATION);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_SPAN_KIND);
              assertThat(attribute.getValue().getStringValue()).isEqualTo("CLIENT");
            });
  }

  protected void assertSemanticConventionsSpanAttributes(
      List<ResourceScopeSpan> resourceScopeSpans, String otelStatusCode, String dbSqlTable) {
    assertThat(resourceScopeSpans)
        .satisfiesOnlyOnce(
            rss -> {
              assertThat(rss.getSpan().getKind()).isEqualTo(SPAN_KIND_CLIENT);
              assertThat(rss.getSpan().getName())
                  .isEqualTo(String.format("%s %s.%s", DB_OPERATION, DB_NAME, dbSqlTable));
              assertThat(rss.getSpan().getStatus().getCode().equals(otelStatusCode));
              var attributesList = rss.getSpan().getAttributesList();
              assertSemanticConventionsAttributes(attributesList, dbSqlTable);
            });
  }

  protected void assertSemanticConventionsAttributes(
      List<KeyValue> attributesList, String dbSqlTable) {
    assertThat(attributesList)
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
                  .isEqualTo(SemanticConventionsConstants.DB_CONNECTION_STRING);
              assertThat(attribute.getValue().getStringValue())
                  .isEqualTo(String.format("%s:mem:", DB_SYSTEM));
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.DB_NAME);
              assertThat(attribute.getValue().getStringValue()).isEqualTo(DB_NAME);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.DB_SQL_TABLE);
              assertThat(attribute.getValue().getStringValue()).isEqualTo(dbSqlTable);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.DB_STATEMENT);
              assertThat(attribute.getValue().getStringValue())
                  .isEqualTo(
                      String.format("%s count(*) from %s", DB_OPERATION.toLowerCase(), dbSqlTable));
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.DB_USER);
              assertThat(attribute.getValue().getStringValue()).isEqualTo(DB_USER);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.DB_OPERATION);
              assertThat(attribute.getValue().getStringValue()).isEqualTo(DB_OPERATION);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.DB_SYSTEM);
              assertThat(attribute.getValue().getStringValue()).isEqualTo(DB_SYSTEM);
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
                                      .isEqualTo(DB_SYSTEM);
                                })
                            .satisfiesOnlyOnce(
                                attribute -> {
                                  assertThat(attribute.getKey())
                                      .isEqualTo(AppSignalsConstants.AWS_REMOTE_OPERATION);
                                  assertThat(attribute.getValue().getStringValue())
                                      .isEqualTo(DB_OPERATION);
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
