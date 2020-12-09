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

pluginManagement {
  plugins {
    id("com.diffplug.spotless") version "5.8.2"
    id("com.github.ben-manes.versions") version "0.36.0"
    id("com.github.jk1.dependency-license-report") version "1.16"
    id("com.github.johnrengelman.shadow") version "6.1.0"
    id("com.google.cloud.tools.jib") version "2.7.0"
    id("io.codearte.nexus-staging") version "0.22.0"
    id("nebula.release") version "15.3.0"
    id("org.springframework.boot") version "2.4.0"
  }
}

include(":awsagentprovider")
include(":dependencyManagement")
include(":otelagent")
include(":smoke-tests:fakebackend")
include(":smoke-tests:runner")
include(":smoke-tests:spring-boot")
include(":sample-apps:springboot")
include(":sample-apps:spark")
