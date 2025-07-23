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
  id("com.gradleup.shadow")
  id("groovy")
}

base.archivesBaseName = "aws-instrumentation-aws-sdk"

dependencies {
  compileOnly("io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api")
  compileOnly("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api")
  compileOnly("com.amazonaws:aws-java-sdk-core:1.11.0")
  compileOnly("software.amazon.awssdk:aws-core:2.2.0")
  compileOnly("software.amazon.awssdk:aws-json-protocol:2.2.0")

  compileOnly("net.bytebuddy:byte-buddy")
  compileOnly("com.google.code.findbugs:jsr305:3.0.2")

  testImplementation("com.google.guava:guava")
  testImplementation("io.opentelemetry.javaagent:opentelemetry-testing-common")

  testImplementation("com.amazonaws:aws-java-sdk-core:1.11.0")
  testImplementation("io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api")
  testImplementation("org.mockito:mockito-core:5.14.2")
}
