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
  id("java")
  id("com.google.cloud.tools.jib")
}

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
  implementation("com.sparkjava:spark-core")
  implementation("org.apache.kafka:kafka-clients:3.6.0")
  implementation("org.slf4j:slf4j-api:2.0.9")
  implementation("org.slf4j:slf4j-simple:2.0.9")
  testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.2")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.2")
}

tasks.getByName<Test>("test") {
  useJUnitPlatform()
}

// not publishing images to hubs in this configuration - local build only through jibDockerBuild
// if localDocker property is set to true then the image will only be pulled from Docker Daemon
tasks {
  named("jib") {
    enabled = false
  }
}
jib {
  configureImages(
    "public.ecr.aws/docker/library/amazoncorretto:17-alpine",
    "aws-appsignals-tests-kafka-kafka-consumers",
    localDocker = rootProject.property("localDocker")!! == "true",
    multiPlatform = false,
  )
}
