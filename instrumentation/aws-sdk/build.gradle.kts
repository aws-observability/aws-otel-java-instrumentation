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

base.archivesBaseName = "aws-instrumentation-awssdk-2.2"

dependencies {

  compileOnly("software.amazon.awssdk:json-utils:2.17.0")

  compileOnly("com.google.auto.service:auto-service:1.1.1")
  compileOnly("com.google.code.findbugs:jsr305:3.0.2")

  compileOnly("io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api")
  compileOnly("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api")
  compileOnly("io.opentelemetry:opentelemetry-sdk")
  compileOnly("io.opentelemetry:opentelemetry-api")

  compileOnly("software.amazon.awssdk:aws-core:2.2.0")
  compileOnly("software.amazon.awssdk:sns:2.2.0")
  compileOnly("software.amazon.awssdk:sqs:2.2.0")
  compileOnly("software.amazon.awssdk:lambda:2.2.0")
  compileOnly("software.amazon.awssdk:aws-json-protocol:2.2.0")
  compileOnly("software.amazon.awssdk:sfn:2.2.0")
  compileOnly("software.amazon.awssdk:lambda:2.2.0")
  compileOnly("software.amazon.awssdk:secretsmanager:2.2.0")

  compileOnly("org.slf4j:slf4j-api:2.0.0")
  compileOnly("org.slf4j:slf4j-simple:2.0.0")

  // Test dependencies
  testImplementation("org.codehaus.groovy:groovy-all:3.0.9")
  testImplementation("org.spockframework:spock-core:2.0-groovy-3.0")
  testImplementation("org.junit.jupiter:junit-jupiter:5.8.1")
  testImplementation("org.mockito:mockito-core:3.12.4")
  testImplementation("org.assertj:assertj-core:3.24.2")
  testImplementation("org.mockito:mockito-junit-jupiter:3.12.4")

  // AWS SDK test dependencies
  testImplementation("software.amazon.awssdk:dynamodb:2.2.0")
  testImplementation("software.amazon.awssdk:ec2:2.2.0")
  testImplementation("software.amazon.awssdk:kinesis:2.2.0")
  testImplementation("software.amazon.awssdk:rds:2.2.0")
  testImplementation("software.amazon.awssdk:s3:2.2.0")
  testImplementation("software.amazon.awssdk:ses:2.2.0")
  testImplementation("software.amazon.awssdk:sfn:2.2.0")
  testImplementation("software.amazon.awssdk:secretsmanager:2.2.0")
  testImplementation("software.amazon.awssdk:lambda:2.2.0")

  testImplementation("io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api")
  testImplementation("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api")
}
