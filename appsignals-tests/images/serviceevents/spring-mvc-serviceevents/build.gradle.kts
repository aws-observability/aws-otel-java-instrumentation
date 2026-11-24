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
  id("com.google.cloud.tools.jib")
  id("org.springframework.boot")
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web:3.4.0")
}

tasks {
  named("jib") {
    enabled = false
  }
}

jib {
  configureImages(
    "public.ecr.aws/docker/library/amazoncorretto:23-alpine",
    "aws-serviceevents-tests-http-server-spring-mvc",
    localDocker = rootProject.property("localDocker")!! == "true",
    multiPlatform = false,
  )
  from {
    platforms {
      platform {
        architecture = if (System.getProperty("os.arch") == "aarch64") "arm64" else "amd64"
        os = "linux"
      }
    }
  }
}
