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
  implementation("com.sparkjava:spark-core")
  implementation("org.slf4j:slf4j-simple")
  implementation(project(":appsignals-tests:images:grpc:grpc-base"))

  implementation("io.grpc:grpc-api:1.56.1")
  implementation("io.grpc:grpc-protobuf:1.56.1")
  implementation("io.grpc:grpc-stub:1.68.1")

  runtimeOnly("io.grpc:grpc-netty-shaded")
}
tasks {
  named("jib") {
    enabled = false
  }
}
jib {
  configureImages(
    "public.ecr.aws/docker/library/amazoncorretto:17-alpine",
    "grpc-client",
    localDocker = rootProject.property("localDocker")!! == "true",
    multiPlatform = false,
  )
}

application {
  mainClass.set("software.amazon.opentelemetry.Main")
}
