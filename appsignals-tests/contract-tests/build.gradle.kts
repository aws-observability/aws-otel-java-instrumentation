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
  kotlin("jvm") version "1.8.22"
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
  testImplementation("io.opentelemetry.proto:opentelemetry-proto")
  testImplementation("org.curioswitch.curiostack:protobuf-jackson")
  testImplementation("org.slf4j:slf4j-simple")
  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("io.opentelemetry.contrib:opentelemetry-aws-xray")
  testImplementation("org.testcontainers:localstack")
  testImplementation("software.amazon.awssdk:s3")
  testImplementation("software.amazon.awssdk:sts")
  testImplementation(kotlin("test"))
  implementation(project(":appsignals-tests:images:grpc:grpc-base"))
  testImplementation("org.testcontainers:kafka:1.19.3")
}

project.evaluationDependsOn(":otelagent")

val otelAgentJarTask = project(":otelagent").tasks.named<Jar>("shadowJar")
tasks {
  withType<Test>().configureEach {
    dependsOn(otelAgentJarTask)

    jvmArgs(
      "-Dio.awsobservability.instrumentation.contracttests.agentPath=${otelAgentJarTask.get().archiveFile.get()
        .getAsFile().absolutePath}",
    )
  }

  // Disable the test task from the java plugin
  named("test") {
    enabled = false
  }

  register<Test>("contractTests") {
    dependsOn("contractTestsImages")
  }

  withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
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
}
