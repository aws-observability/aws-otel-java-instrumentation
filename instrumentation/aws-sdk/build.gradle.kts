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
  id("groovy")
  id("com.gradleup.shadow")
}

base.archivesBaseName = "aws-instrumentation-awssdk"

dependencies {

  compileOnly("software.amazon.awssdk:json-utils:2.17.0")
  compileOnly("com.google.code.findbugs:jsr305:3.0.2")

  // OpenTelemetry dependencies
  compileOnly("io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api")
  compileOnly("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api")

  // AWS SDK dependencies for version 1.11
  compileOnly("com.amazonaws:aws-java-sdk-core:1.11.0")
  compileOnly("com.amazonaws:aws-java-sdk-sqs:1.11.106")

  // AWS SDK dependencies for version 2.2.0
  compileOnly("software.amazon.awssdk:aws-core:2.2.0")
  compileOnly("software.amazon.awssdk:lambda:2.2.0")
  compileOnly("software.amazon.awssdk:aws-json-protocol:2.2.0")
  compileOnly("software.amazon.awssdk:sfn:2.2.0")
  compileOnly("software.amazon.awssdk:secretsmanager:2.2.0")

  // Test dependencies
  testImplementation(project(":instrumentation:aws-sdk:testing"))
  testImplementation("io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api")

  // AWS SDK v1.11 test dependencies
  testImplementation("com.amazonaws:aws-java-sdk-sqs:1.11.106")
  testImplementation("com.amazonaws:aws-java-sdk-secretsmanager:1.11.309")
  testImplementation("com.amazonaws:aws-java-sdk-stepfunctions:1.11.230")
  testImplementation("com.amazonaws:aws-java-sdk-lambda:1.11.678")
  testImplementation("com.amazonaws:aws-java-sdk-bedrock:1.12.744")
  testImplementation("com.amazonaws:aws-java-sdk-bedrockagent:1.12.744")
  testImplementation("com.amazonaws:aws-java-sdk-bedrockagentruntime:1.12.744")
  testImplementation("com.amazonaws:aws-java-sdk-bedrockruntime:1.12.744")

  // AWS SDK v2.2 test dependencies
  testImplementation("software.amazon.awssdk:dynamodb:2.2.0")
  testImplementation("software.amazon.awssdk:ec2:2.2.0")
  testImplementation("software.amazon.awssdk:kinesis:2.2.0")
  testImplementation("software.amazon.awssdk:rds:2.2.0")
  testImplementation("software.amazon.awssdk:s3:2.2.0")
  testImplementation("software.amazon.awssdk:ses:2.2.0")
  testImplementation("software.amazon.awssdk:sfn:2.2.0")
  testImplementation("software.amazon.awssdk:secretsmanager:2.2.0")
  testImplementation("software.amazon.awssdk:lambda:2.2.0")
}
