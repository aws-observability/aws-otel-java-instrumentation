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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ServiceEventsAwsEndpointTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://logs.us-east-1.amazonaws.com/v1/logs",
        "https://logs.cn-north-1.amazonaws.com.cn/v1/logs",
        "https://logs.cn-northwest-1.amazonaws.com.cn/v1/logs"
      })
  void testAwsOtlpLogsEndpointSupportsCommercialAndChinaPartitions(String endpoint) {
    assertTrue(ServiceEventsInstrumentation.isAwsOtlpLogsEndpoint(endpoint));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://logs.cn-north-1.amazonaws.com.cn.evil.example/v1/logs",
        "https://logs.cn-north-1.amazonaws.comcn/v1/logs",
        "https://logs.cn-north-1.amazonaws.cn/v1/logs"
      })
  void testAwsOtlpLogsEndpointRejectsInvalidChinaSuffixes(String endpoint) {
    assertFalse(ServiceEventsInstrumentation.isAwsOtlpLogsEndpoint(endpoint));
  }
}
