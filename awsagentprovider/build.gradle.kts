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

plugins {
  java
  id("com.github.johnrengelman.shadow")
}

base {
  archivesBaseName = "aws-opentelemetry-agent-providers"
}

dependencies {
  compileOnly("io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api")
  compileOnly("io.opentelemetry.semconv:opentelemetry-semconv")
  testImplementation("io.opentelemetry.semconv:opentelemetry-semconv")
  compileOnly("com.google.errorprone:error_prone_annotations:2.19.1")
  compileOnly("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure-spi")
  compileOnly("org.slf4j:slf4j-api")

  // Trace ID Generation and Sampling Rules
  implementation("io.opentelemetry.contrib:opentelemetry-aws-xray")
  // AWS Resource Detectors
  implementation("io.opentelemetry.contrib:opentelemetry-aws-resources")
  // Json file reader
  implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
  // Import AWS SDK v1 core for ARN parsing utilities
  implementation("com.amazonaws:aws-java-sdk-core")
  // Export configuration
  compileOnly("io.opentelemetry:opentelemetry-exporter-otlp")
  // SDK Lambda for Lambda ARN retrieval
  implementation("com.amazonaws:aws-java-sdk-lambda")

  testImplementation("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure")
  testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
  testImplementation("io.opentelemetry:opentelemetry-extension-aws")
  testImplementation("io.opentelemetry:opentelemetry-extension-trace-propagators")
  testImplementation("com.google.guava:guava")

  compileOnly("com.google.code.findbugs:jsr305:3.0.2")
  testImplementation("org.mockito:mockito-core:5.3.1")
  testImplementation("org.mockito:mockito-junit-jupiter:5.8.0")
}

tasks {
  shadowJar {
    archiveClassifier.set("")
  }
}
