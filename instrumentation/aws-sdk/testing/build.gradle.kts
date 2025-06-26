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
