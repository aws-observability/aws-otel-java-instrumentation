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
    "software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.app.AutoconfigureApp"
  )
}

dependencies {
  implementation(platform("io.opentelemetry:opentelemetry-bom:${(project.findProperty("otelBomVersion") as String?) ?: "1.45.0"}"))
  implementation("io.opentelemetry:opentelemetry-api")
  implementation("io.opentelemetry:opentelemetry-sdk")
  implementation("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure")
  implementation("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure-spi")
  implementation("io.opentelemetry:opentelemetry-exporter-otlp")

  // Bake the span-metrics extension onto the classpath. Its
  // AutoConfigurationCustomizerProvider SPI fires automatically during autoconfigure.
  implementation(files("../../../build/libs/aws-otel-span-metrics-extension-1.0.0.jar"))

  implementation("com.h2database:h2:2.2.224")
}

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
    image = "aws-otel-span-metrics-autoconfigure-app"
  }
  container {
    ports = listOf("8080")
  }
}
