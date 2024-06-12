package software.amazon.opentelemetry.appsignals.test.jdbc.operationtests;

import static io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_CLIENT;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogramDataPoint;
import software.amazon.opentelemetry.appsignals.test.utils.AppSignalsConstants;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeMetric;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeSpan;
import software.amazon.opentelemetry.appsignals.test.utils.SemanticConventionsConstants;

public abstract class JdbcOperationTester {

    protected final String dbSystem;
    protected final String dbUser;
    protected final String jdbcUrl;
    protected final String dbName;
    protected final String dbOperation;
    protected final String dbTable;

    protected JdbcOperationTester(String dbSystem, String dbUser, String jdbcUrl, String dbName, String dbOperation, String dbTable) {
        this.dbSystem = dbSystem;
        this.dbUser = dbUser;
        this.jdbcUrl = jdbcUrl;
        this.dbName = dbName;
        this.dbOperation = dbOperation;
        this.dbTable = dbTable;
    }

    public void assertAwsSpanAttributes(List<ResourceScopeSpan> resourceScopeSpans, String method, String path, String type,
            String identifier, String applicationOtelServiceName) {
        assertThat(resourceScopeSpans).satisfiesOnlyOnce(rss -> {
            assertThat(rss.getSpan().getKind()).isEqualTo(SPAN_KIND_CLIENT);
            var attributesList = rss.getSpan().getAttributesList();
            assertAwsAttributes(attributesList, method, path, type, identifier, applicationOtelServiceName);
        });
    }

    private void assertAwsAttributes(List<KeyValue> attributesList, String method, String endpoint, String type,
            String identifier, String applicationOtelServiceName) {
        var assertions = assertThat(attributesList).satisfiesOnlyOnce(attribute -> {
            assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_LOCAL_OPERATION);
            assertThat(attribute.getValue().getStringValue()).isEqualTo(String.format("%s /%s", method, endpoint));
        }).satisfiesOnlyOnce(attribute -> {
            assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_LOCAL_SERVICE);
            assertThat(attribute.getValue().getStringValue()).isEqualTo(applicationOtelServiceName);
        }).satisfiesOnlyOnce(attribute -> {
            assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_REMOTE_SERVICE);
            assertThat(attribute.getValue().getStringValue()).isEqualTo(this.dbSystem);
        }).satisfiesOnlyOnce(attribute -> {
            assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_REMOTE_OPERATION);
            assertThat(attribute.getValue().getStringValue()).isEqualTo(this.dbOperation);
        }).satisfiesOnlyOnce(attribute -> {
            assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_SPAN_KIND);
            assertThat(attribute.getValue().getStringValue()).isEqualTo("CLIENT");
        });
        if (type != null && identifier != null) {
            assertions.satisfiesOnlyOnce((attribute) -> {
                assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_REMOTE_RESOURCE_TYPE);
                assertThat(attribute.getValue().getStringValue()).isEqualTo(type);
            });
            assertions.satisfiesOnlyOnce((attribute) -> {
                assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_REMOTE_RESOURCE_IDENTIFIER);
                assertThat(attribute.getValue().getStringValue()).isEqualTo(identifier);
            });
        }
    }

    public void assertSemanticConventionsSpanAttributes(List<ResourceScopeSpan> resourceScopeSpans, String otelStatusCode) {
        assertThat(resourceScopeSpans).satisfiesOnlyOnce(rss -> {
            assertOperationSemanticConventions(rss);
            assertSemanticConventionsCommon(rss, otelStatusCode);
        });
    }

    protected abstract void assertOperationSemanticConventions(ResourceScopeSpan resourceScopeSpan);

    private void assertSemanticConventionsCommon (ResourceScopeSpan rss, String otelStatusCode) {
        assertThat(rss.getSpan().getKind()).isEqualTo(SPAN_KIND_CLIENT);
        assertThat(rss.getSpan().getStatus().getCode().toString()).isEqualTo(otelStatusCode);
        var attributesList = rss.getSpan().getAttributesList();
        assertSemanticConventionsAttributes(attributesList);
    }

    protected void assertSemanticConventionsAttributes(List<KeyValue> attributesList) {
        assertThat(attributesList).satisfiesOnlyOnce(attribute -> {
            assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.THREAD_ID);
        }).satisfiesOnlyOnce(attribute -> {
            assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.THREAD_NAME);
        }).satisfiesOnlyOnce(attribute -> {
            assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.DB_CONNECTION_STRING);
            assertThat(attribute.getValue().getStringValue()).isEqualTo(this.jdbcUrl);
        }).satisfiesOnlyOnce(attribute -> {
            assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.DB_NAME);
            assertThat(attribute.getValue().getStringValue()).isEqualTo(this.dbName);
        }).satisfiesOnlyOnce(attribute -> {
            assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.DB_USER);
            assertThat(attribute.getValue().getStringValue()).isEqualTo(this.dbUser);
        }).satisfiesOnlyOnce(attribute -> {
            assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.DB_SYSTEM);
            assertThat(attribute.getValue().getStringValue()).isEqualTo(this.dbSystem);
        });
    }

    public void assertMetricAttributes(List<ResourceScopeMetric> resourceScopeMetrics, String method, String path,
            String metricName, Double expectedSum, String type, String identifier, String applicationOtelServiceName) {
        assertThat(resourceScopeMetrics).anySatisfy(metric -> {
            assertThat(metric.getMetric().getName()).isEqualTo(metricName);
            List<ExponentialHistogramDataPoint> dpList = metric.getMetric().getExponentialHistogram().getDataPointsList();
            assertThat(dpList).satisfiesOnlyOnce(dp -> {
                List<KeyValue> attributesList = dp.getAttributesList();
                assertThat(attributesList).isNotNull();
                var assertions = assertThat(attributesList).satisfiesOnlyOnce(attribute -> {
                    assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_SPAN_KIND);
                    assertThat(attribute.getValue().getStringValue()).isEqualTo("CLIENT");
                }).satisfiesOnlyOnce(attribute -> {
                    assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_LOCAL_OPERATION);
                    assertThat(attribute.getValue().getStringValue()).isEqualTo(String.format("%s /%s", method, path));
                }).satisfiesOnlyOnce(attribute -> {
                    assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_LOCAL_SERVICE);
                    assertThat(attribute.getValue().getStringValue()).isEqualTo(applicationOtelServiceName);
                }).satisfiesOnlyOnce(attribute -> {
                    assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_REMOTE_SERVICE);
                    assertThat(attribute.getValue().getStringValue()).isEqualTo(this.dbSystem);
                }).satisfiesOnlyOnce(attribute -> {
                    assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_REMOTE_OPERATION);
                    assertThat(attribute.getValue().getStringValue()).isEqualTo(this.dbOperation);
                });
                if (type != null && identifier != null) {
                    assertions.satisfiesOnlyOnce((attribute) -> {
                        assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_REMOTE_RESOURCE_TYPE);
                        assertThat(attribute.getValue().getStringValue()).isEqualTo(type);
                    });
                    assertions.satisfiesOnlyOnce((attribute) -> {
                        assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_REMOTE_RESOURCE_IDENTIFIER);
                        assertThat(attribute.getValue().getStringValue()).isEqualTo(identifier);
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

}
