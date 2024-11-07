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

import software.amazon.adot.configureImages

plugins {
  java
  application
  id("com.google.cloud.tools.jib")
}

group = "software.amazon.opentelemetry"

dependencies {
  implementation(project(":appsignals-tests:images:grpc:grpc-base"))
  implementation("javax.annotation:javax.annotation-api:1.3.2")

  implementation("io.grpc:grpc-api:1.56.1")
  implementation("io.grpc:grpc-protobuf:1.56.1")
  implementation("io.grpc:grpc-stub:1.68.1")

  runtimeOnly("io.grpc:grpc-netty-shaded")
  testImplementation(platform("org.junit:junit-bom:5.9.1"))
  testImplementation("org.junit.jupiter:junit-jupiter")
}

application {
  mainClass.set("software.amazon.opentelemetry.EchoerServer")
}
tasks {
  named("jib") {
    enabled = false
  }
}
jib {
  configureImages(
    "public.ecr.aws/docker/library/amazoncorretto:17-alpine",
    "grpc-server",
    localDocker = rootProject.property("localDocker")!! == "true",
    multiPlatform = false,
  )
}
