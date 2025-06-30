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
  `java-library`
  id("com.gradleup.shadow")
}

dependencies {
  api("io.opentelemetry.javaagent:opentelemetry-testing-common")

  api("software.amazon.awssdk:apache-client:2.2.0")
  api("software.amazon.awssdk:netty-nio-client:2.11.0")
  api("com.amazonaws:aws-java-sdk-core:1.11.0")

  // AWS SDK dependencies for version 1.11
  compileOnly("com.amazonaws:aws-java-sdk-sns:1.11.106")
  compileOnly("com.amazonaws:aws-java-sdk-secretsmanager:1.11.309")
  compileOnly("com.amazonaws:aws-java-sdk-stepfunctions:1.11.230")
  compileOnly("com.amazonaws:aws-java-sdk-lambda:1.11.678")
  compileOnly("com.amazonaws:aws-java-sdk-bedrock:1.12.744")
  compileOnly("com.amazonaws:aws-java-sdk-bedrockagent:1.12.744")
  compileOnly("com.amazonaws:aws-java-sdk-bedrockagentruntime:1.12.744")
  compileOnly("com.amazonaws:aws-java-sdk-bedrockruntime:1.12.744")

  // AWS SDK dependencies for version 2.2.0
  compileOnly("software.amazon.awssdk:aws-core:2.2.0")
  compileOnly("software.amazon.awssdk:sdk-core:2.2.0")
  compileOnly("software.amazon.awssdk:dynamodb:2.2.0")
  compileOnly("software.amazon.awssdk:ec2:2.2.0")
  compileOnly("software.amazon.awssdk:kinesis:2.2.0")
  compileOnly("software.amazon.awssdk:lambda:2.2.0")
  compileOnly("software.amazon.awssdk:rds:2.2.0")
  compileOnly("software.amazon.awssdk:s3:2.2.0")
  compileOnly("software.amazon.awssdk:sqs:2.2.0")
  compileOnly("software.amazon.awssdk:sns:2.2.0")
  compileOnly("software.amazon.awssdk:ses:2.2.0")
  compileOnly("software.amazon.awssdk:sfn:2.2.0")
  compileOnly("software.amazon.awssdk:secretsmanager:2.2.0")

  implementation("com.google.guava:guava")
  implementation("io.opentelemetry:opentelemetry-api")
}
