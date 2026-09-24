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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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
  kotlin("jvm") version "2.1.0-RC2"
}

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
  testImplementation("com.google.guava:guava")
  testImplementation("com.linecorp.armeria:armeria")
  testImplementation("com.linecorp.armeria:armeria-grpc")
  testImplementation("io.opentelemetry:opentelemetry-api")
  // Bumped to 1.10.0-alpha (from the shared platform's 1.0.0-alpha) so MockCollectorClient can
  // deserialize the native OTLP profiles signal (ExportProfilesServiceRequest,
  // profiles.v1development.*) captured by the mock collector's /v1development/profiles route.
  // 1.10.0-alpha still carries the trace/logs/metrics v1 packages the other contract tests use.
  // protobuf-java 4.34.0 matches the 1.10.0-alpha gencode (overrides the shared protobuf-bom
  // 3.25.1); scoped to this module's test classpath.
  testImplementation("io.opentelemetry.proto:opentelemetry-proto:1.10.0-alpha")
  testImplementation("com.google.protobuf:protobuf-java:4.34.0")
  // protobuf-jackson 2.2.0 (the shared platform pin) is protobuf-java 3.x only and throws
  // IllegalAccessError on FieldDescriptor.hasOptionalKeyword() under protobuf-java 4.x. 2.8.1
  // targets protobuf-java 4.x — required for MockCollectorClient to (de)serialize any signal
  // (trace/logs/metrics/profiles) on the 4.34.0 runtime the profiles proto pulls in. Scoped to this
  // module's test classpath (overrides the platform 2.2.0); the smoke-tests stay on 2.2.0 + 3.x.
  testImplementation("org.curioswitch.curiostack:protobuf-jackson:2.8.1")
  testImplementation("org.slf4j:slf4j-simple")
  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("io.opentelemetry.contrib:opentelemetry-aws-xray")
  testImplementation("org.testcontainers:localstack")
  testImplementation("software.amazon.awssdk:s3")
  testImplementation("software.amazon.awssdk:sts")
  testImplementation(kotlin("test"))
  implementation(project(":appsignals-tests:images:grpc:grpc-base"))
  testImplementation("org.testcontainers:kafka:1.21.3")
  testImplementation("org.testcontainers:postgresql:1.21.3")
  testImplementation("org.testcontainers:mysql:1.21.3")
  testImplementation("com.mysql:mysql-connector-j:8.4.0")
  // Used by Dynamic Instrumentation contract tests to parse/validate captured snapshot JSON.
  testImplementation("com.fasterxml.jackson.core:jackson-databind")
  testImplementation("com.github.luben:zstd-jni:1.5.6-4")
}

project.evaluationDependsOn(":otelagent")

val otelAgentJarTask = project(":otelagent").tasks.named<Jar>("shadowJar")
tasks {
  withType<Test>().configureEach {
    dependsOn(otelAgentJarTask)

    val agentPath = otelAgentJarTask.get().archiveFile.get().getAsFile().absolutePath
    jvmArgs(
      "-Dio.awsobservability.instrumentation.contracttests.agentPath=$agentPath",
      "-Dio.awsobservability.di.contracttests.agentPath=$agentPath",
    )

    // Forward Docker-related env vars to the test JVM for Testcontainers.
    listOf("DOCKER_HOST", "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "TESTCONTAINERS_RYUK_DISABLED").forEach { envVar ->
      System.getenv(envVar)?.let { environment(envVar, it) }
    }
    System.getenv("DOCKER_API_VERSION")?.let { systemProperty("api.version", it) }

    // On macOS (Docker Desktop), set defaults for Testcontainers compatibility.
    if (System.getProperty("os.name").lowercase().contains("mac")) {
      if (System.getenv("DOCKER_HOST") == null) {
        environment("DOCKER_HOST", "unix:///var/run/docker.sock")
      }
      if (System.getenv("DOCKER_API_VERSION") == null) {
        environment("DOCKER_API_VERSION", "1.44")
        systemProperty("api.version", "1.44")
      }
    }
  }

  // Disable the test task from the java plugin
  named("test") {
    enabled = false
  }

  register<Test>("contractTests") {
    dependsOn("contractTestsImages")
    // Dynamic Instrumentation and ServiceEvents tests each have their own task (diContractTests,
    // serviceeventsContractTests) and their own test image, which contractTestsImages does not
    // build. Exclude them here so they are not run (and fail with a missing-image initialization
    // error) as part of the standard contract test suite.
    exclude("**/di/**")
    exclude("**/serviceevents/**")
  }

  register<Test>("diContractTests") {
    dependsOn("diContractTestsImages")
    include("**/di/**")
  }

  withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
  }

  register<Test>("serviceeventsContractTests") {
    dependsOn("serviceeventsContractTestsImages")
    include("**/serviceevents/**")
  }

  register("serviceeventsContractTestsImages") {
    dependsOn(":appsignals-tests:images:serviceevents:spring-mvc-serviceevents:jibDockerBuild")
    dependsOn(":appsignals-tests:images:mock-collector:jibDockerBuild")
  }

  register("contractTestsImages") {
    // Make sure that images used during tests are available locally.
    dependsOn(":appsignals-tests:images:mock-collector:jibDockerBuild")
    dependsOn(":appsignals-tests:images:http-servers:spring-mvc:jibDockerBuild")
    dependsOn(":appsignals-tests:images:http-servers:tomcat:jibDockerBuild")
    dependsOn(":appsignals-tests:images:http-servers:netty-server:jibDockerBuild")
    dependsOn(":appsignals-tests:images:http-clients:native-http-client:jibDockerBuild")
    dependsOn(":appsignals-tests:images:http-clients:spring-mvc-client:jibDockerBuild")
    dependsOn(":appsignals-tests:images:http-clients:apache-http-client:jibDockerBuild")
    dependsOn(":appsignals-tests:images:http-clients:netty-http-client:jibDockerBuild")
    dependsOn(":appsignals-tests:images:aws-sdk:aws-sdk-v1:jibDockerBuild")
    dependsOn(":appsignals-tests:images:aws-sdk:aws-sdk-v2:jibDockerBuild")
    dependsOn(":appsignals-tests:images:grpc:grpc-client:jibDockerBuild")
    dependsOn(":appsignals-tests:images:grpc:grpc-server:jibDockerBuild")
    dependsOn(":appsignals-tests:images:jdbc:jibDockerBuild")
    dependsOn(":appsignals-tests:images:kafka:kafka-producers:jibDockerBuild")
    dependsOn(":appsignals-tests:images:kafka:kafka-consumers:jibDockerBuild")
  }

  register("diContractTestsImages") {
    dependsOn(":appsignals-tests:images:di:di-spring-boot:jibDockerBuild")
    dependsOn(":appsignals-tests:images:mock-collector:jibDockerBuild")
  }
}
