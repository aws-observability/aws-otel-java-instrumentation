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

import software.amazon.adot.configureImages

plugins {
  java

  application
  id("com.google.cloud.tools.jib")
}

dependencies {
  implementation("io.opentelemetry:opentelemetry-api")
  implementation("com.sparkjava:spark-core")
  implementation("com.google.code.gson:gson:2.10.1")
  implementation("org.slf4j:slf4j-simple")
  implementation(project(":appsignals-tests:images:aws-sdk:aws-sdk-base"))
  implementation(enforcedPlatform("com.amazonaws:aws-java-sdk-bom:1.12.761"))
  implementation("com.amazonaws:aws-java-sdk-s3")
  implementation("com.amazonaws:aws-java-sdk-dynamodb")
  implementation("com.amazonaws:aws-java-sdk-sqs")
  implementation("com.amazonaws:aws-java-sdk-kinesis")
  implementation("com.amazonaws:aws-java-sdk-bedrock")
  implementation("com.amazonaws:aws-java-sdk-bedrockagent")
  implementation("com.amazonaws:aws-java-sdk-bedrockruntime")
  implementation("com.amazonaws:aws-java-sdk-bedrockagentruntime")
  implementation("commons-logging:commons-logging")
  implementation("com.linecorp.armeria:armeria")
  implementation("com.fasterxml.jackson.core:jackson-databind:2.13.3")
}

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}
tasks {
  named("jib") {
    enabled = false
  }
}
jib {
  configureImages(
    "public.ecr.aws/docker/library/amazoncorretto:17-alpine",
    "aws-appsignals-tests-aws-sdk-v1",
    localDocker = rootProject.property("localDocker")!! == "true",
    multiPlatform = false,
  )
}

application {
  mainClass.set("com.amazon.sampleapp.App")
}
