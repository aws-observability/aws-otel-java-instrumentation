/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

plugins {
  java
}

dependencies {
  testImplementation("com.google.protobuf:protobuf-java-util")
  testImplementation("com.linecorp.armeria:armeria")
  testImplementation("io.opentelemetry:opentelemetry-proto")
  testImplementation("org.slf4j:slf4j-simple")
  testImplementation("org.testcontainers:junit-jupiter")
}

project.evaluationDependsOn(":otelagent")

val otelAgentJarTask = project(":otelagent").tasks.named<Jar>("shadowJar")
tasks {
  named<Test>("test") {
    enabled = findProperty("io.awsobservability.smoketests") == "true"

    dependsOn(otelAgentJarTask)

    jvmArgs(
      "-Dio.awsobservability.instrumentation.smoketests.runner.agentPath=${otelAgentJarTask.get().archiveFile.get()
        .getAsFile().absolutePath}"
    )
  }
}
