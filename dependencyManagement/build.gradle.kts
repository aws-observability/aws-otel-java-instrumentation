/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
  `java-platform`
}

data class DependencySet(val group: String, val version: String, val modules: List<String>)

val DEPENDENCY_BOMS = listOf(
  "com.amazonaws:aws-xray-recorder-sdk-bom:2.6.1",
  "com.fasterxml.jackson:jackson-bom:2.11.0",
  "io.grpc:grpc-bom:1.29.0",
  "io.zipkin.brave:brave-bom:5.12.3",
  "io.zipkin.reporter2:zipkin-reporter-bom:2.15.0",
  "org.apache.logging.log4j:log4j-bom:2.13.3",
  "org.springframework.boot:spring-boot-dependencies:2.2.7.RELEASE",
  "software.amazon.awssdk:bom:2.13.17"
)

val DEPENDENCY_SETS = listOf(
  DependencySet(
    "io.opentelemetry.instrumentation.auto",
    "0.8.0-20200812.182934-26",
    listOf(
      "opentelemetry-javaagent",
      "opentelemetry-auto-exporter-otlp"
    )
  ),
  DependencySet(
    "io.opentelemetry",
    "0.8.0-20200812.153234-21",
    listOf(
      "opentelemetry-api",
      "opentelemetry-extension-trace-propagators",
      "opentelemetry-exporters-otlp",
      "opentelemetry-sdk",
      "opentelemetry-sdk-extension-aws-v1-support"
    )
  )
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
  }
}
