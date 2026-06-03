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
  `java-library`
}

description = "DI Bootstrap Bridge - Loaded by bootstrap classloader for cross-classloader data sharing"

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

// This module should have NO dependencies - it goes into bootstrap classloader
dependencies {
  // No dependencies allowed!
}

tasks {
  jar {
    archiveBaseName.set("di-bootstrap-bridge")
  }
}
