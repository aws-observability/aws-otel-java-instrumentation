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
  application
  java
  id("com.google.cloud.tools.jib") version "3.4.0"
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

application {
  mainClass.set(
    "software.amazon.opentelemetry.cloudwatch.spanmetrics.e2e.mockcollector.Main"
  )
}

dependencies {
  implementation(platform("com.linecorp.armeria:armeria-bom:1.26.4"))
  implementation(platform("io.grpc:grpc-bom:1.59.1"))
  implementation(platform("com.google.guava:guava-bom:33.0.0-jre"))
  implementation(platform("com.fasterxml.jackson:jackson-bom:2.21.4"))

  implementation("com.linecorp.armeria:armeria")
  implementation("com.linecorp.armeria:armeria-grpc")
  implementation("io.opentelemetry.proto:opentelemetry-proto:1.0.0-alpha")
  implementation("org.curioswitch.curiostack:protobuf-jackson:2.2.0")
  implementation("com.fasterxml.jackson.core:jackson-databind")
  implementation("com.google.guava:guava")
  implementation("org.slf4j:slf4j-simple:1.7.36")
}

// Local-only image build through jibDockerBuild. The push-oriented `jib` task is disabled.
tasks {
  named("jib") {
    enabled = false
  }
}

jib {
  from {
    image = "public.ecr.aws/docker/library/amazoncorretto:23-alpine"
  }
  to {
    image = "cloudwatch-plugin-otel-mock-collector"
  }
  container {
    ports = listOf("4317")
  }
}
