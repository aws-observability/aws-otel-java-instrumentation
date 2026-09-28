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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the logs endpoint pattern that decides whether ServiceEvents logs are exported with
 * SigV4 directly to CloudWatch Logs or through a collector.
 */
class ServiceEventsInstrumentationTest {

  private static String awsOtlpLogsEndpointPattern() throws Exception {
    Field field =
        ServiceEventsInstrumentation.class.getDeclaredField("AWS_OTLP_LOGS_ENDPOINT_PATTERN");
    field.setAccessible(true);
    return (String) field.get(null);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://logs.us-east-1.amazonaws.com/v1/logs",
        "https://logs.cn-north-1.amazonaws.com.cn/v1/logs",
        "https://logs.cn-northwest-1.amazonaws.com.cn/v1/logs"
      })
  void logsEndpointPattern_matchesAwsEndpoints(String endpoint) throws Exception {
    assertTrue(endpoint.matches(awsOtlpLogsEndpointPattern()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://localhost:4318/v1/logs",
        "https://logs.cn-north-1.amazonaws.cn/v1/logs",
        "https://logs.cn-north-1.amazonaws.com.cn.example.com/v1/logs",
        "https://logs.cn-north-1.amazonaws.com-cn/v1/logs"
      })
  void logsEndpointPattern_rejectsOtherEndpoints(String endpoint) throws Exception {
    assertFalse(endpoint.matches(awsOtlpLogsEndpointPattern()));
  }
}
