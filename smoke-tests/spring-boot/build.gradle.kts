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
  id("com.google.cloud.tools.jib")
  id("org.springframework.boot")
}

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
}

jib {
  configureImages(
    "public.ecr.aws/u8q5x3l1/aws-otel-test/aws-opentelemetry-java-base:alpha",
    "public.ecr.aws/u8q5x3l1/aws-otel-test/aws-otel-java-smoketests-springboot",
    localDocker = rootProject.property("localDocker")!!.equals("true"),
    multiPlatform = false,
  )
}

tasks {
  named("jib") {
    dependsOn(":otelagent:jib")
  }
  named("jibDockerBuild") {
    dependsOn(":otelagent:jibDockerBuild")
  }
}
