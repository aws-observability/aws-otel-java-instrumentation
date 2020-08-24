/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
  id("com.google.cloud.tools.jib") version "2.5.0"
  id("org.springframework.boot") version "2.3.3.RELEASE"
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
  configurations.configureEach {
    if (name.endsWith("Classpath")) {
      add(name, enforcedPlatform("org.springframework.boot:spring-boot-dependencies:2.3.3.RELEASE"))
    }
  }

  implementation("org.springframework.boot:spring-boot-starter-web")
}

jib {
  to {
    image = "docker.pkg.github.com/anuraaga/aws-opentelemetry-java-instrumentation/smoke-tests-spring-boot:master"
  }
}
