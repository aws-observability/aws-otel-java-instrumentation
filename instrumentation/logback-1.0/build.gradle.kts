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

base.archivesBaseName = "aws-instrumentation-logback-1.0"

dependencies {
  compileOnly("io.opentelemetry:opentelemetry-api")
  compileOnly("io.opentelemetry.instrumentation:opentelemetry-logback-mdc-1.0")
  compileOnly("io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api")
  compileOnly("net.bytebuddy:byte-buddy")

  compileOnly("ch.qos.logback:logback-classic:1.4.6")
}
