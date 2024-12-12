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

package software.amazon.opentelemetry.appsignals.test.kafka.producers;

import static io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_PRODUCER;
import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogramDataPoint;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.PullPolicy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.lifecycle.Startable;
import org.testcontainers.utility.DockerImageName;
import software.amazon.opentelemetry.appsignals.test.base.ContractTestBase;
import software.amazon.opentelemetry.appsignals.test.utils.AppSignalsConstants;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeMetric;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeSpan;
import software.amazon.opentelemetry.appsignals.test.utils.SemanticConventionsConstants;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class KafkaProducersTest extends ContractTestBase {
  private KafkaContainer kafka;

  @Test
  public void testSuccess() {

    var path = "success";
    var method = "GET";
    var kafkaTopic = "kafka_topic";
    var otelStatusCode = "STATUS_CODE_UNSET";
    var response = appClient.get(path).aggregate().join();
    assertThat(response.status().isSuccess()).isTrue();

    var resourceScopeSpans = mockCollectorClient.getTraces();
    assertAwsSpanAttributes(resourceScopeSpans, method, path);
    assertSemanticConventionsSpanAttributes(resourceScopeSpans, otelStatusCode, kafkaTopic);

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
    var kafkaTopic = "fault_do_not_exist";
    var otelStatusCode = "STATUS_CODE_ERROR";
    var response =
        appClient
            .prepare()
            .get(path)
            .responseTimeout(Duration.ofSeconds(15))
            .execute()
            .aggregate()
            .join();

    assertThat(response.status().isServerError()).isTrue();

    var resourceScopeSpans = mockCollectorClient.getTraces();
    assertAwsSpanAttributes(resourceScopeSpans, method, path);
    assertSemanticConventionsSpanAttributes(resourceScopeSpans, otelStatusCode, kafkaTopic);

    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.LATENCY_METRIC,
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC));
    assertMetricAttributes(metrics, method, path, AppSignalsConstants.LATENCY_METRIC, 50000.0);
    assertMetricAttributes(metrics, method, path, AppSignalsConstants.ERROR_METRIC, 0.0);
    assertMetricAttributes(metrics, method, path, AppSignalsConstants.FAULT_METRIC, 1.0);
  }

  @Override
  protected List<Startable> getApplicationDependsOnContainers() {
    kafka =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"))
            .withImagePullPolicy(PullPolicy.alwaysPull())
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
            .withNetworkAliases("kafkaBroker")
            .withNetwork(network)
            .waitingFor(Wait.forLogMessage(".* Kafka Server started .*", 1))
            .withKraft();
    return List.of(kafka);
  }

  @BeforeAll
  public void setup() throws IOException, InterruptedException {
    kafka.start();
    kafka.execInContainer(
        "/bin/sh",
        "-c",
        "/usr/bin/kafka-topics --bootstrap-server=localhost:9092 --create --topic kafka_topic --partitions 1 --replication-factor 1");
  }

  @AfterAll
  public void tearDown() {
    kafka.stop();
  }

  @Override
  protected String getApplicationImageName() {
    return "aws-appsignals-tests-kafka-kafka-producers";
  }

  @Override
  protected String getApplicationWaitPattern() {
    return ".*Routes ready.*";
  }

  protected void assertAwsSpanAttributes(
      List<ResourceScopeSpan> resourceScopeSpans, String method, String path) {
    assertThat(resourceScopeSpans)
        .satisfiesOnlyOnce(
            rss -> {
              assertThat(rss.getSpan().getKind()).isEqualTo(SPAN_KIND_PRODUCER);
              var attributesList = rss.getSpan().getAttributesList();
              assertAwsAttributes(attributesList, method, path);
            });
  }

  protected void assertSemanticConventionsSpanAttributes(
      List<ResourceScopeSpan> resourceScopeSpans, String otelStatusCode, String kafkaTopic) {
    assertThat(resourceScopeSpans)
        .satisfiesOnlyOnce(
            rss -> {
              assertThat(rss.getSpan().getKind()).isEqualTo(SPAN_KIND_PRODUCER);
              assertThat(rss.getSpan().getName())
                  .isEqualTo(String.format("%s publish", kafkaTopic));
              assertThat(rss.getSpan().getStatus().getCode().equals(otelStatusCode));
              var attributesList = rss.getSpan().getAttributesList();
              assertSemanticConventionsAttributes(attributesList, kafkaTopic);
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
              assertThat(attribute.getValue().getStringValue()).isEqualTo("kafka");
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_REMOTE_OPERATION);
              assertThat(attribute.getValue().getStringValue()).isEqualTo("publish");
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(AppSignalsConstants.AWS_SPAN_KIND);
              assertThat(attribute.getValue().getStringValue()).isEqualTo("PRODUCER");
            });
  }

  protected void assertSemanticConventionsAttributes(
      List<KeyValue> attributesList, String kafkaTopic) {
    assertThat(attributesList)
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey())
                  .isEqualTo(SemanticConventionsConstants.MESSAGING_CLIENT_ID);
              assertThat(attribute.getValue().getStringValue()).isEqualTo("producer-1");
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey())
                  .isEqualTo(SemanticConventionsConstants.MESSAGING_DESTINATION_NAME);
              assertThat(attribute.getValue().getStringValue()).isEqualTo(kafkaTopic);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey())
                  .isEqualTo(SemanticConventionsConstants.MESSAGING_DESTINATION_PARTITION_ID);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey())
                  .isEqualTo(SemanticConventionsConstants.MESSAGING_KAFKA_MESSAGE_OFFSET);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey())
                  .isEqualTo(SemanticConventionsConstants.MESSAGING_SYSTEM);
              assertThat(attribute.getValue().getStringValue()).isEqualTo("kafka");
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey())
                  .isEqualTo(SemanticConventionsConstants.MESSAGING_OPERATION);
              assertThat(attribute.getValue().getStringValue()).isEqualTo("publish");
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
                                      .isEqualTo("PRODUCER");
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
                                      .isEqualTo("kafka");
                                })
                            .satisfiesOnlyOnce(
                                attribute -> {
                                  assertThat(attribute.getKey())
                                      .isEqualTo(AppSignalsConstants.AWS_REMOTE_OPERATION);
                                  assertThat(attribute.getValue().getStringValue())
                                      .isEqualTo("publish");
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
