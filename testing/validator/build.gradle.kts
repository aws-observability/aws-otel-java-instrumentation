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
  id("java")

  id("application")

  // lombok
  id("io.freefair.lombok") version "8.1.0"
}

repositories {
  mavenCentral()

  maven(
    "https://jitpack.io",
  )
  maven {
    setUrl("https://oss.sonatype.org/content/repositories/snapshots")
  }
}

dependencies {
  // junit
  testImplementation("org.junit.jupiter:junit-jupiter-api")

  // log
  implementation(group = "org.apache.logging.log4j", name = "log4j-api", version = "2.20.0")
  implementation(group = "org.apache.logging.log4j", name = "log4j-core", version = "2.20.0")

  // mustache template
  implementation(group = "com.github.spullara.mustache.java", name = "compiler", version = "0.9.10")

  // apache io utils
  implementation(group = "commons-io", name = "commons-io", version = "2.12.0")

  // yaml reader
  implementation(group = "com.fasterxml.jackson.dataformat", name = "jackson-dataformat-yaml", version = "2.15.1")

  // json flattener
  implementation(group = "com.github.wnameless", name = "json-flattener", version = "0.7.1")
  implementation(group = "com.github.fge", name = "json-schema-validator", version = "2.0.0")

  // command cli
  implementation("info.picocli:picocli:4.7.3")

  compileOnly("info.picocli:picocli-codegen:4.7.3")

  // aws sdk
  implementation(platform("com.amazonaws:aws-java-sdk-bom:1.12.506"))
  implementation("com.amazonaws:aws-java-sdk-s3")
  implementation("com.amazonaws:aws-java-sdk-cloudwatch")
  implementation("com.amazonaws:aws-java-sdk-xray")
  implementation("com.amazonaws:aws-java-sdk-logs")
  implementation("com.amazonaws:aws-java-sdk-sts")

  // aws ecs sdk
  implementation("com.amazonaws:aws-java-sdk-ecs")

  // https://mvnrepository.com/artifact/com.github.awslabs/aws-request-signing-apache-interceptor
  implementation("com.github.awslabs:aws-request-signing-apache-interceptor:b3772780da")

  // http client
  implementation("com.squareup.okhttp3:okhttp:4.9.3")

  // command cli
  implementation("info.picocli:picocli:4.7.3")

  compileOnly("info.picocli:picocli-codegen:4.7.3")

  // mockito
  testImplementation("org.mockito:mockito-core:5.3.1")
}

application {
  // Define the main class for the application.
  mainClass.set("com.amazon.aoc.App")
}
