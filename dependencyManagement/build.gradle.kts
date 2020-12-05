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

import com.github.jk1.license.LicenseReportExtension

plugins {
  `java-platform`
}

data class DependencySet(val group: String, val version: String, val modules: List<String>)

val DEPENDENCY_BOMS = listOf(
  "com.amazonaws:aws-xray-recorder-sdk-bom:2.6.1",
  "com.fasterxml.jackson:jackson-bom:2.11.0",
  "com.google.protobuf:protobuf-bom:3.13.0",
  "com.linecorp.armeria:armeria-bom:1.0.0",
  "io.grpc:grpc-bom:1.30.2",
  "io.opentelemetry:opentelemetry-bom:0.11.0",
  "io.zipkin.brave:brave-bom:5.12.3",
  "io.zipkin.reporter2:zipkin-reporter-bom:2.15.0",
  "org.apache.logging.log4j:log4j-bom:2.13.3",
  "org.junit:junit-bom:5.7.0-RC1",
  "org.springframework.boot:spring-boot-dependencies:2.3.3.RELEASE",
  "org.testcontainers:testcontainers-bom:1.15.0-rc2",
  "software.amazon.awssdk:bom:2.15.39"
)

val DEPENDENCY_SETS = listOf(
  DependencySet(
    "com.google.guava",
    "29.0-jre",
    listOf("guava", "guava-testlib")
  ),
  DependencySet(
    "io.opentelemetry.javaagent",
    "0.11.0",
    listOf(
      "opentelemetry-javaagent",
      "opentelemetry-javaagent-spi"
    )
  ),
  DependencySet(
    "org.assertj",
    "3.17.0",
    listOf("assertj-core")
  ),
  DependencySet(
    "org.curioswitch.curiostack",
    "1.1.0",
    listOf("protobuf-jackson")
  ),
  DependencySet(
    "org.slf4j",
    "1.7.30",
    listOf(
      "slf4j-api",
      "slf4j-simple"
    )
  )
)

val DEPENDENCIES = listOf(
  "com.sparkjava:spark-core:2.9.3",
  "com.squareup.okhttp3:okhttp:3.14.9"
)

javaPlatform {
  allowDependencies()
}

dependencies {
  for (bom in DEPENDENCY_BOMS) {
    api(platform(bom))
  }
  constraints {
    for (set in DEPENDENCY_SETS) {
      for (module in set.modules) {
        api("${set.group}:$module:${set.version}")
      }
    }

    for (dependency in DEPENDENCIES) {
      api(dependency)
    }
  }
}

rootProject.allprojects {
  plugins.withId("com.github.jk1.dependency-license-report") {
    configure<LicenseReportExtension> {
      val bomExcludes = DEPENDENCY_BOMS.stream()
        .map { it.substring(0, it.lastIndexOf(':')) }
        .toArray { length -> arrayOfNulls<String>(length) }
      excludes = bomExcludes
    }
  }
}
