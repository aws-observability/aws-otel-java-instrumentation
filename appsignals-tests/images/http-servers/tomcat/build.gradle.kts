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
  application
  id("com.google.cloud.tools.jib")
  id("org.springframework.boot")
}

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

application {
  mainClass.set("software.amazon.opentelemetry.appsignals.tests.images.httpservers.tomcat.Main")
}

dependencies {
  implementation("org.apache.tomcat.embed:tomcat-embed-core:11.0.1")
  implementation("io.opentelemetry:opentelemetry-api")
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
    "aws-appsignals-tests-http-server-tomcat",
    localDocker = rootProject.property("localDocker")!! == "true",
    multiPlatform = false,
  )
}
