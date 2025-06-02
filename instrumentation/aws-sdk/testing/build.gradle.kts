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
  id("com.gradleup.shadow")
}

val awsSdkVersion = "2.2.0"
val otelVersion = "2.10.0-adot2"

dependencies {
  testImplementation(
    "io.opentelemetry.javaagent:opentelemetry-testing-common:$otelVersion-alpha",
  )

  compileOnly("software.amazon.awssdk:apache-client:$awsSdkVersion")
  compileOnly("software.amazon.awssdk:netty-nio-client:2.11.0")

  // compileOnly because we never want to pin the low version implicitly; need to add dependencies
  // explicitly in user projects, e.g. using testLatestDeps.
  compileOnly("software.amazon.awssdk:dynamodb:$awsSdkVersion")
  compileOnly("software.amazon.awssdk:ec2:$awsSdkVersion")
  compileOnly("software.amazon.awssdk:kinesis:$awsSdkVersion")
  compileOnly("software.amazon.awssdk:lambda:$awsSdkVersion")
  compileOnly("software.amazon.awssdk:rds:$awsSdkVersion")
  compileOnly("software.amazon.awssdk:s3:$awsSdkVersion")
  compileOnly("software.amazon.awssdk:sqs:$awsSdkVersion")
  compileOnly("software.amazon.awssdk:sns:$awsSdkVersion")
  compileOnly("software.amazon.awssdk:ses:$awsSdkVersion")
  compileOnly("software.amazon.awssdk:sfn:$awsSdkVersion")
  compileOnly("software.amazon.awssdk:lambda:$awsSdkVersion")
  compileOnly("software.amazon.awssdk:secretsmanager:$awsSdkVersion")

  // needed for SQS - using emq directly as localstack references emq v0.15.7 ie WITHOUT AWS trace header propagation
  implementation("org.elasticmq:elasticmq-rest-sqs_2.13")

  implementation("com.google.guava:guava")

  implementation("org.apache.groovy:groovy")
  implementation("io.opentelemetry:opentelemetry-api")
  implementation("org.spockframework:spock-core")
}
