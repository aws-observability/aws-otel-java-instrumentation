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
}

base.archivesBaseName = "aws-instrumentation-aws-lambda"

dependencies {
  compileOnly("com.google.code.findbugs:jsr305:3.0.2")
  compileOnly("io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api")
  compileOnly("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api")
  compileOnly("io.opentelemetry:opentelemetry-sdk")
  compileOnly("net.bytebuddy:byte-buddy")
  compileOnly("com.amazonaws:aws-lambda-java-core:1.0.0")

  compileOnly("com.google.auto.value:auto-value-annotations:1.10.1")
  annotationProcessor("com.google.auto.value:auto-value:1.10.1")
}
