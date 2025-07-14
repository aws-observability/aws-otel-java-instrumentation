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

base.archivesBaseName = "aws-instrumentation-aws-sdk"

dependencies {
  compileOnly("io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api")
  compileOnly("com.amazonaws:aws-java-sdk-core:1.11.0")
  compileOnly("software.amazon.awssdk:aws-core:2.2.0")
  compileOnly("net.bytebuddy:byte-buddy")
}
