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

// -PotelInstrumentationVersion pins the Spring Boot starter (instrumentation) release so CI can run
// the Spring mode across the supported range. The starter brings its own SDK, so this exercises the
// plugin against the SDK each starter release ships (the raw SDK floor is covered by the
// manual/autoconfigure modes). "latest" tracks the newest release.
val otelInstrumentationVersion =
  (project.findProperty("otelInstrumentationVersion") as String?) ?: "2.10.0"
val resolvedInstrumentationVersion =
  if (otelInstrumentationVersion == "latest") "latest.release" else otelInstrumentationVersion

configurations.configureEach {
  // Always re-resolve "latest.release" so a nightly run picks up new starter releases.
  resolutionStrategy.cacheDynamicVersionsFor(0, "seconds")
}

// Spring Boot's dependency management pins its own (older) OpenTelemetry version, which downgrades
// the starter's transitive SDK/API and breaks a newer starter (missing classes). Importing the
// instrumentation BOM here (last import wins) makes it — and the matching core SDK BOM it pulls in —
// govern the io.opentelemetry version graph, keeping it internally consistent across the tested
// starter range.
the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().imports {
  mavenBom(
    "io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:$resolvedInstrumentationVersion"
  )
}

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  runtimeOnly("com.h2database:h2")

  // OpenTelemetry Spring Boot starter provides the OpenTelemetry bean + auto instrumentation. The
  // instrumentation BOM is imported into Spring's dependency management above so it governs the
  // io.opentelemetry version graph.
  implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter")

  // Bake the span-metrics extension onto the classpath. Its Spring auto-configuration and its
  // AutoConfigurationCustomizerProvider SPI fire automatically under the starter.
  implementation(files(fileTree("../../../build/libs") { include("cloudwatch-plugin-otel-*.jar"); exclude("*-sources.jar", "*-javadoc.jar") }.singleFile))
}

// The Spring Boot fat jar is what jib containerizes.
jib {
  from {
    image = "public.ecr.aws/docker/library/amazoncorretto:23-alpine"
  }
  to {
    image = "cloudwatch-plugin-otel-spring-app"
  }
  container {
    ports = listOf("8080")
    mainClass =
      "software.amazon.opentelemetry.cloudwatch.spanmetrics.e2e.app.SpringApp"
  }
}
