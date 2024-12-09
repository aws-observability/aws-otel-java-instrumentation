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

package software.amazon.opentelemetry.appsignals.test.misc.jmx;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.opentelemetry.appsignals.test.base.JMXMetricsContractTestBase;
import software.amazon.opentelemetry.appsignals.test.utils.JMXMetricsConstants;

/**
 * Tests in this class validate that the SDK will emit JVM metrics when Application Signals runtime
 * metrics are enabled and if tomcat is also enabled, it will emit those metrics.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TomcatMetricsTest extends JMXMetricsContractTestBase {
  @Test
  void testTomcatMetrics() {
    doTestMetrics();
  }

  @Override
  protected String getApplicationImageName() {
    return "aws-appsignals-tests-http-server-tomcat";
  }

  @Override
  protected String getApplicationWaitPattern() {
    return ".*Starting ProtocolHandler.*";
  }

  @Override
  protected Set<String> getExpectedMetrics() {
    return JMXMetricsConstants.TOMCAT_METRICS_SET;
  }

  @Override
  protected Map<String, String> getApplicationExtraEnvironmentVariables() {
    return Map.of("OTEL_JMX_TARGET_SYSTEM", "tomcat");
  }
}
