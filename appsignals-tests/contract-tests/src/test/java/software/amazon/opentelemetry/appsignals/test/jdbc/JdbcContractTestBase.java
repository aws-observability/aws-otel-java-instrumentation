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
import java.util.Set;
import software.amazon.opentelemetry.appsignals.test.base.ContractTestBase;
import software.amazon.opentelemetry.appsignals.test.utils.AppSignalsConstants;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeMetric;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeSpan;
import software.amazon.opentelemetry.appsignals.test.utils.SemanticConventionsConstants;

public class JdbcContractTestBase extends ContractTestBase {
  protected static final String DB_NAME = "testdb";
  protected static final String CREATE_DB_NAME = "testdb2";
  protected static final String DB_USER = "root";
  protected static final String DB_PASSWORD = "password";
  protected static final String DB_SELECT_OPERATION = "SELECT";
  protected static final String DB_CREATE_DATABASE_OPERATION = "CREATE database";
  protected static final String DB_RESOURCE_TYPE = "DB::Connection";

  @Override
  protected String getApplicationImageName() {
    return "aws-appsignals-tests-jdbc-app";
  }

  @Override
  protected String getApplicationWaitPattern() {
    return ".*Application Ready.*";
  }

  protected void assertAwsSpanAttributes(
      List<ResourceScopeSpan> resourceScopeSpans,
      String method,
      String path,
      String dbSystem,
      String dbOperation,
      String dbUser,
      String type,
      String identifier) {
    assertThat(resourceScopeSpans)
        .satisfiesOnlyOnce(
            rss -> {
              assertThat(rss.getSpan().getKind()).isEqualTo(SPAN_KIND_CLIENT);
              var attributesList = rss.getSpan().getAttributesList();
              assertAwsAttributes(
                  attributesList, method, path, dbSystem, dbOperation, dbUser, type, identifier);
            });
  }

  protected void assertAwsAttributes(
      List<KeyValue> attributesList,
      String method,
      String endpoint,
      String dbSystem,
      String dbOperation,
      String dbUser,
      String type,
      String identifier) {
    var assertions =
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
                  assertThat(attribute.getValue().getStringValue()).isEqualTo(dbSystem);
                })
            .satisfiesOnlyOnce(
                attribute -> {
                  assertThat(attribute.getKey())
                      .isEqualTo(AppSignalsConstants.AWS_REMOTE_OPERATION);
                  assertThat(attribute.getValue().getStringValue()).isEqualTo(dbOperation);
                })
            .satisfiesOnlyOnce(
                attribute -> {
                  assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_REMOTE_DB_USER);
                  assertThat(attribute.getValue().getStringValue()).isEqualTo(dbUser);
                })
            .satisfiesOnlyOnce(
                attribute -> {
                  assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_SPAN_KIND);
                  assertThat(attribute.getValue().getStringValue()).isEqualTo("CLIENT");
                });
    if (type != null && identifier != null) {
      assertions.satisfiesOnlyOnce(
          (attribute) -> {
            assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_REMOTE_RESOURCE_TYPE);
            assertThat(attribute.getValue().getStringValue()).isEqualTo(type);
          });
      assertions.satisfiesOnlyOnce(
          (attribute) -> {
            assertThat(attribute.getKey())
                .isEqualTo(AppSignalsConstants.AWS_REMOTE_RESOURCE_IDENTIFIER);
            assertThat(attribute.getValue().getStringValue()).isEqualTo(identifier);
          });
    } else {
      assertions.allSatisfy(
          (attribute) -> {
            assertThat(attribute.getKey())
                .isNotEqualTo(AppSignalsConstants.AWS_REMOTE_RESOURCE_TYPE);
            assertThat(attribute.getKey())
                .isNotEqualTo(AppSignalsConstants.AWS_REMOTE_RESOURCE_IDENTIFIER);
          });
    }
  }

  protected void assertSemanticConventionsSpanAttributes(
      List<ResourceScopeSpan> resourceScopeSpans,
      String otelStatusCode,
      String dbSqlTable,
      String dbSystem,
      String dbOperation,
      String dbUser,
      String dbName,
      String jdbcUrl) {
    assertThat(resourceScopeSpans)
        .satisfiesOnlyOnce(
            rss -> {
              assertThat(rss.getSpan().getKind()).isEqualTo(SPAN_KIND_CLIENT);
              assertThat(rss.getSpan().getStatus().getCode().toString()).isEqualTo(otelStatusCode);
              assertSemanticConventionForOperation(rss, dbOperation, dbName, dbSqlTable);
              var attributesList = rss.getSpan().getAttributesList();
              assertSemanticConventionsAttributes(
                  attributesList, dbSystem, dbUser, dbName, jdbcUrl);
              assertSemanticConventionsAttributesForOperation(
                  attributesList, dbOperation, dbSqlTable);
            });
  }

  private void assertSemanticConventionForOperation(
      ResourceScopeSpan rss, String dbOperation, String dbName, String dbSqlTable) {
    if (dbOperation.equals(DB_CREATE_DATABASE_OPERATION)) {
      assertThat(rss.getSpan().getName()).isEqualTo(String.format("%s %s", dbOperation, dbName));
    } else if (dbOperation.equals(DB_SELECT_OPERATION)) {
      assertThat(rss.getSpan().getName())
          .isEqualTo(String.format("%s %s.%s", dbOperation, dbName, dbSqlTable));
    }
  }

  protected void assertSemanticConventionsAttributes(
      List<KeyValue> attributesList,
      String dbSystem,
      String dbUser,
      String dbName,
      String jdbcUrl) {
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
              assertThat(attribute.getValue().getStringValue()).isEqualTo(jdbcUrl);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.DB_NAME);
              assertThat(attribute.getValue().getStringValue()).isEqualTo(dbName);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.DB_USER);
              assertThat(attribute.getValue().getStringValue()).isEqualTo(dbUser);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.DB_SYSTEM);
              assertThat(attribute.getValue().getStringValue()).isEqualTo(dbSystem);
            });
  }

  private void assertSemanticConventionsAttributesForOperation(
      List<KeyValue> attributesList, String dbOperation, String dbSqlTable) {
    if (dbOperation.equals(DB_CREATE_DATABASE_OPERATION)) {
      assertThat(attributesList)
          .satisfiesOnlyOnce(
              attribute -> {
                assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.DB_STATEMENT);
                assertThat(attribute.getValue().getStringValue())
                    .isEqualTo(String.format("%s %s", dbOperation.toLowerCase(), CREATE_DB_NAME));
              });
    } else if (dbOperation.equals(DB_SELECT_OPERATION)) {
      assertThat(attributesList)
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
                        String.format(
                            "%s count(*) from %s", dbOperation.toLowerCase(), dbSqlTable));
              })
          .satisfiesOnlyOnce(
              attribute -> {
                assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.DB_OPERATION);
                assertThat(attribute.getValue().getStringValue()).isEqualTo(dbOperation);
              });
    }
  }

  protected void assertMetricAttributes(
      List<ResourceScopeMetric> resourceScopeMetrics,
      String method,
      String path,
      String metricName,
      Double expectedSum,
      String dbSystem,
      String dbOperation,
      String type,
      String identifier) {
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
                        var assertions =
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
                                          .isEqualTo(dbSystem);
                                    })
                                .satisfiesOnlyOnce(
                                    attribute -> {
                                      assertThat(attribute.getKey())
                                          .isEqualTo(AppSignalsConstants.AWS_REMOTE_OPERATION);
                                      assertThat(attribute.getValue().getStringValue())
                                          .isEqualTo(dbOperation);
                                    });
                        if (type != null && identifier != null) {
                          assertions.satisfiesOnlyOnce(
                              (attribute) -> {
                                assertThat(attribute.getKey())
                                    .isEqualTo(AppSignalsConstants.AWS_REMOTE_RESOURCE_TYPE);
                                assertThat(attribute.getValue().getStringValue()).isEqualTo(type);
                              });
                          assertions.satisfiesOnlyOnce(
                              (attribute) -> {
                                assertThat(attribute.getKey())
                                    .isEqualTo(AppSignalsConstants.AWS_REMOTE_RESOURCE_IDENTIFIER);
                                assertThat(attribute.getValue().getStringValue())
                                    .isEqualTo(identifier);
                              });
                        }

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

  protected void assertSuccess(
      String dbSystem,
      String dbOperation,
      String dbUser,
      String dbName,
      String jdbcUrl,
      String type,
      String identifier) {
    var path = "success/" + dbOperation;
    var method = "GET";
    var otelStatusCode = "STATUS_CODE_UNSET";
    var dbSqlTable = "employee";

    var response = appClient.get(path).aggregate().join();
    assertThat(response.status().isSuccess()).isTrue();

    var traces = mockCollectorClient.getTraces();
    assertAwsSpanAttributes(traces, method, path, dbSystem, dbOperation, dbUser, type, identifier);
    assertSemanticConventionsSpanAttributes(
        traces, otelStatusCode, dbSqlTable, dbSystem, dbOperation, dbUser, dbName, jdbcUrl);

    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.LATENCY_METRIC,
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC));
    assertMetricAttributes(
        metrics,
        method,
        path,
        AppSignalsConstants.LATENCY_METRIC,
        5000.0,
        dbSystem,
        dbOperation,
        type,
        identifier);
    assertMetricAttributes(
        metrics,
        method,
        path,
        AppSignalsConstants.ERROR_METRIC,
        0.0,
        dbSystem,
        dbOperation,
        type,
        identifier);
    assertMetricAttributes(
        metrics,
        method,
        path,
        AppSignalsConstants.FAULT_METRIC,
        0.0,
        dbSystem,
        dbOperation,
        type,
        identifier);
  }

  protected void assertFault(
      String dbSystem,
      String dbOperation,
      String dbUser,
      String dbName,
      String jdbcUrl,
      String type,
      String identifier) {
    var path = "fault/" + dbOperation;
    var method = "GET";
    var otelStatusCode = "STATUS_CODE_ERROR";
    var dbSqlTable = "userrr";
    var response = appClient.get(path).aggregate().join();
    assertThat(response.status().isServerError()).isTrue();

    var traces = mockCollectorClient.getTraces();
    assertAwsSpanAttributes(traces, method, path, dbSystem, dbOperation, dbUser, type, identifier);
    assertSemanticConventionsSpanAttributes(
        traces, otelStatusCode, dbSqlTable, dbSystem, dbOperation, dbUser, dbName, jdbcUrl);

    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.LATENCY_METRIC,
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC));
    assertMetricAttributes(
        metrics,
        method,
        path,
        AppSignalsConstants.LATENCY_METRIC,
        5000.0,
        dbSystem,
        dbOperation,
        type,
        identifier);
    assertMetricAttributes(
        metrics,
        method,
        path,
        AppSignalsConstants.ERROR_METRIC,
        0.0,
        dbSystem,
        dbOperation,
        type,
        identifier);
    assertMetricAttributes(
        metrics,
        method,
        path,
        AppSignalsConstants.FAULT_METRIC,
        1.0,
        dbSystem,
        dbOperation,
        type,
        identifier);
  }
}
