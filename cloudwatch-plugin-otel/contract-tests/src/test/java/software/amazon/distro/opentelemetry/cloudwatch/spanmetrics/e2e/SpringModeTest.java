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

package software.amazon.distro.opentelemetry.cloudwatch.spanmetrics.e2e;

import org.testcontainers.junit.jupiter.Testcontainers;

/** Mode 2: the OpenTelemetry Spring Boot starter discovers the extension on the classpath. */
@Testcontainers(disabledWithoutDocker = true)
class SpringModeTest extends AbstractModeTest {

  @Override
  protected String getApplicationImageName() {
    return "cloudwatch-plugin-otel-spring-app";
  }

  @Override
  protected String getApplicationWaitPattern() {
    return ".*Started SpringApp.*";
  }

  @Override
  protected String databaseSpanName() {
    // Spring Data JPA over H2 names the JDBC span by operation + namespace.entity.
    return "SELECT spanmetrics.test_item";
  }
}
