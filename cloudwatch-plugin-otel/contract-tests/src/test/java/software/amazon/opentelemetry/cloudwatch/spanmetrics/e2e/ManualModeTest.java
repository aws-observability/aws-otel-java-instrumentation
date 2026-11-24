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

package software.amazon.opentelemetry.cloudwatch.spanmetrics.e2e;

import org.testcontainers.junit.jupiter.Testcontainers;

/** Mode 4: the app builds the SDK by hand and calls SpanMetrics.bind(). */
@Testcontainers(disabledWithoutDocker = true)
class ManualModeTest extends AbstractModeTest {

  @Override
  protected String getApplicationImageName() {
    return "cloudwatch-plugin-otel-manual-app";
  }

  @Override
  protected String databaseSpanName() {
    return "SELECT test_items";
  }
}
