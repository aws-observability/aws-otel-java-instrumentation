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
  id("org.springframework.boot") version "3.4.0"
  id("io.spring.dependency-management") version "1.1.6"
  id("com.google.cloud.tools.jib") version "3.4.0"
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  runtimeOnly("com.h2database:h2")

  // OpenTelemetry Spring Boot starter provides the OpenTelemetry bean + auto instrumentation.
  implementation(
    platform("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:2.10.0")
  )
  implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter")

  // Bake the span-metrics extension onto the classpath. Its Spring auto-configuration and its
  // AutoConfigurationCustomizerProvider SPI fire automatically under the starter.
  implementation(files("../../../build/libs/aws-otel-span-metrics-extension-1.0.0.jar"))
}

// The Spring Boot fat jar is what jib containerizes.
jib {
  from {
    image = "public.ecr.aws/docker/library/amazoncorretto:23-alpine"
  }
  to {
    image = "aws-otel-span-metrics-spring-app"
  }
  container {
    ports = listOf("8080")
    mainClass =
      "software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.app.SpringApp"
  }
}
