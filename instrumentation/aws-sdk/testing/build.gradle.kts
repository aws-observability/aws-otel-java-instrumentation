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

val awsSdkVersion = "2.2.0"
val otelVersion = "2.11.0-adot1"

dependencies {
  api("io.opentelemetry.javaagent:opentelemetry-testing-common:$otelVersion-alpha")

  api("software.amazon.awssdk:apache-client:$awsSdkVersion")
  api("software.amazon.awssdk:netty-nio-client:2.11.0")

  // Change these from compileOnly to implementation for testing
  implementation("software.amazon.awssdk:aws-core:$awsSdkVersion")
  implementation("software.amazon.awssdk:sdk-core:$awsSdkVersion")
  implementation("software.amazon.awssdk:dynamodb:$awsSdkVersion")
  implementation("software.amazon.awssdk:ec2:$awsSdkVersion")
  implementation("software.amazon.awssdk:kinesis:$awsSdkVersion")
  implementation("software.amazon.awssdk:lambda:$awsSdkVersion")
  implementation("software.amazon.awssdk:rds:$awsSdkVersion")
  implementation("software.amazon.awssdk:s3:$awsSdkVersion")
  implementation("software.amazon.awssdk:sqs:$awsSdkVersion")
  implementation("software.amazon.awssdk:sns:$awsSdkVersion")
  implementation("software.amazon.awssdk:ses:$awsSdkVersion")
  implementation("software.amazon.awssdk:sfn:$awsSdkVersion")
  implementation("software.amazon.awssdk:secretsmanager:$awsSdkVersion")

  // Other dependencies remain the same
  implementation("com.google.guava:guava")
  implementation("org.apache.groovy:groovy")
  implementation("io.opentelemetry:opentelemetry-api")
  implementation("org.spockframework:spock-core")
}

// Add this to handle duplicates of AbstractAwsClientCoreTest (keeps first occurance)
tasks.withType<Jar> {
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

sourceSets {
  test {
    groovy {
      srcDirs("src/main/groovy")
    }
  }
}
