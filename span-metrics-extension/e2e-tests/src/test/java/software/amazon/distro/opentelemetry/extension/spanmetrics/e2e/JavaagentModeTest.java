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

package software.amazon.distro.opentelemetry.extension.spanmetrics.e2e;

import java.util.HashMap;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * Mode 1: the upstream OpenTelemetry javaagent loads the extension via {@code
 * OTEL_JAVAAGENT_EXTENSIONS}. The app itself has no OpenTelemetry dependencies; the agent
 * instruments its JDBC calls.
 */
@Testcontainers(disabledWithoutDocker = true)
class JavaagentModeTest extends AbstractModeTest {

  @Override
  protected String getApplicationImageName() {
    return "aws-otel-span-metrics-javaagent-app";
  }

  @Override
  protected void configureContainer(GenericContainer<?> container) {
    container
        .withCopyFileToContainer(
            MountableFile.forHostPath(JAVAAGENT_JAR_PATH), AGENT_MOUNT_PATH)
        .withCopyFileToContainer(
            MountableFile.forHostPath(EXTENSION_JAR_PATH), EXTENSION_MOUNT_PATH);
  }

  @Override
  protected Map<String, String> getApplicationExtraEnvironmentVariables() {
    Map<String, String> env = new HashMap<>();
    env.put("JAVA_TOOL_OPTIONS", "-javaagent:" + AGENT_MOUNT_PATH);
    env.put("OTEL_JAVAAGENT_EXTENSIONS", EXTENSION_MOUNT_PATH);
    return env;
  }

  @Override
  protected String databaseSpanName() {
    // Named by the agent's JDBC instrumentation: operation + db.namespace.table.
    return "SELECT spanmetrics.test_items";
  }

  @Override
  protected boolean databaseSpanHasSemconvAttributes() {
    // The javaagent 2.29.0 JDBC instrumentation emits legacy db.system, not db.system.name, so the
    // extension (current-semconv only) does not copy database attributes here.
    return false;
  }
}
