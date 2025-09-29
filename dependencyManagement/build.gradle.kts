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

import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import com.github.jk1.license.LicenseReportExtension

plugins {
  `java-platform`

  id("com.github.ben-manes.versions")
}

data class DependencySet(val group: String, val version: String, val modules: List<String>)

val testSnapshots = rootProject.findProperty("testUpstreamSnapshots") == "true"

// This is the version of the upstream instrumentation BOM
val otelVersion = "2.20.1"
val otelSnapshotVersion = "2.21.0"
val otelAlphaVersion = if (!testSnapshots) "$otelVersion-alpha" else "$otelSnapshotVersion-alpha-SNAPSHOT"
val otelJavaAgentVersion = if (!testSnapshots) otelVersion else "$otelSnapshotVersion-SNAPSHOT"
// All versions below are only used in testing and do not affect the released artifact.

val dependencyBoms = listOf(
  "com.amazonaws:aws-java-sdk-bom:1.12.599",
  "com.fasterxml.jackson:jackson-bom:2.16.0",
  "com.google.guava:guava-bom:33.0.0-jre",
  "com.google.protobuf:protobuf-bom:3.25.1",
  "com.linecorp.armeria:armeria-bom:1.26.4",
  "io.grpc:grpc-bom:1.59.1",
  // netty-bom is a fix for CVE-2025-58056 (https://github.com/advisories/GHSA-fghv-69vj-qj49).
  // Remove once https://github.com/aws/aws-sdk-java-v2/pull/6398 and https://github.com/aws/aws-sdk-java/pull/3192
  // are both merged and released, and we update the corresponding dependencies.
  "io.netty:netty-bom:4.1.126.Final",
  "io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom-alpha:$otelAlphaVersion",
  "org.apache.logging.log4j:log4j-bom:2.21.1",
  "org.junit:junit-bom:5.10.1",
  "org.springframework.boot:spring-boot-dependencies:2.7.17",
  "org.testcontainers:testcontainers-bom:1.19.3",
  "software.amazon.awssdk:bom:2.30.17",
)

val dependencySets = listOf(
  DependencySet(
    "org.assertj",
    "3.24.2",
    listOf("assertj-core"),
  ),
  DependencySet(
    "org.curioswitch.curiostack",
    "2.2.0",
    listOf("protobuf-jackson"),
  ),
  DependencySet(
    "org.slf4j",
    "1.7.36",
    listOf(
      "slf4j-api",
      "slf4j-simple",
    ),
  ),
)

val dependencyLists = listOf(
  "commons-logging:commons-logging:1.2",
  "com.sparkjava:spark-core:2.9.4",
  "com.squareup.okhttp3:okhttp:4.12.0",
  "io.opentelemetry.contrib:opentelemetry-aws-xray:1.50.0",
  "io.opentelemetry.contrib:opentelemetry-aws-resources:1.50.0",
  "io.opentelemetry.proto:opentelemetry-proto:1.7.0-alpha",
  "io.opentelemetry.javaagent:opentelemetry-javaagent:$otelJavaAgentVersion",
  "io.opentelemetry:opentelemetry-extension-aws:1.20.1",
  "net.bytebuddy:byte-buddy:1.14.10",
)

javaPlatform {
  allowDependencies()
}

dependencies {
  for (bom in dependencyBoms) {
    api(platform(bom))
  }
  constraints {
    for (set in dependencySets) {
      for (module in set.modules) {
        api("${set.group}:$module:${set.version}")
      }
    }

    for (dependency in dependencyLists) {
      api(dependency)
    }
  }
}

rootProject.allprojects {
  plugins.withId("com.github.jk1.dependency-license-report") {
    configure<LicenseReportExtension> {
      val bomExcludes = dependencyBoms.stream()
        .map { it.substring(0, it.lastIndexOf(':')) }
        .toArray { length -> arrayOfNulls<String>(length) }
      excludes = bomExcludes
    }
  }
}

fun isNonStable(version: String): Boolean {
  val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.toUpperCase().contains(it) }
  val regex = "^[0-9,.v-]+(-r|-alpha)?$".toRegex()
  val isGuava = version.endsWith("-jre")
  val isStable = stableKeyword || regex.matches(version) || isGuava
  return isStable.not()
}

tasks {
  named<DependencyUpdatesTask>("dependencyUpdates") {
    revision = "release"
    checkConstraints = true

    rejectVersionIf {
      isNonStable(candidate.version)
    }
  }
}
