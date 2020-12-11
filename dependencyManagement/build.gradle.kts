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
  "com.fasterxml.jackson:jackson-bom:2.12.0",
  "com.google.protobuf:protobuf-bom:3.14.0",
  "com.linecorp.armeria:armeria-bom:1.3.0",
  "io.grpc:grpc-bom:1.34.0",
  "io.opentelemetry:opentelemetry-bom:0.12.0",
  "org.apache.logging.log4j:log4j-bom:2.14.0",
  "org.junit:junit-bom:5.7.0",
  "org.springframework.boot:spring-boot-dependencies:2.4.0",
  "org.testcontainers:testcontainers-bom:1.15.0",
  "software.amazon.awssdk:bom:2.15.40"
)

val DEPENDENCY_SETS = listOf(
  DependencySet(
    "com.google.guava",
    "29.0-jre",
    listOf("guava", "guava-testlib")
  ),
  DependencySet(
    "io.opentelemetry.javaagent",
    "0.12.1",
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
  "commons-logging:commons-logging:1.2",
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
