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
  compileOnly("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure-spi")
  compileOnly("org.slf4j:slf4j-api")

  implementation("io.opentelemetry:opentelemetry-sdk-extension-aws")
  implementation("io.opentelemetry.contrib:opentelemetry-aws-xray")

  testImplementation("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure")

  testImplementation("com.google.guava:guava")

  compileOnly("com.google.code.findbugs:jsr305:3.0.2")
}

tasks {
  shadowJar {
    archiveClassifier.set("")
  }
}
