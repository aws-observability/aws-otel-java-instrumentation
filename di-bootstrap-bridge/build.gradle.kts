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

// This module must have NO runtime/compile dependencies — it is loaded by the bootstrap
// classloader and embedded in every application regardless of whether DI is enabled.
//
// Test-only dependencies are permitted: they are scoped to the test classpath and never shipped in
// the jar. They are declared with explicit versions here because this module is intentionally
// excluded from the shared dependencyManagement platform (see the root build.gradle.kts), so the
// unversioned test dependencies the root applies to all java projects cannot be resolved otherwise.
dependencies {
  // No main (compile/runtime) dependencies allowed!

  testImplementation(platform("org.junit:junit-bom:5.10.1"))
  testImplementation("org.junit.jupiter:junit-jupiter-api")
  testImplementation("org.assertj:assertj-core:3.24.2")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
}

tasks {
  jar {
    archiveBaseName.set("di-bootstrap-bridge")
  }
}
