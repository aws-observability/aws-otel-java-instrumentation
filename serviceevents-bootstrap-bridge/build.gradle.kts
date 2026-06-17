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

description = "ServiceEvents Native Bootstrap Bridge - Loaded by bootstrap classloader for JNI sharing"

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

// This bridge is loaded by the bootstrap classloader and must run on Java 8+. source/target
// compatibility only controls the bytecode version, not the API surface — pin the release flag so a
// Java 9+ JDK API reference (e.g. ProcessHandle, String.repeat) fails the build instead of throwing
// NoClassDefFoundError/NoSuchMethodError at runtime on Java 8.
tasks.named<JavaCompile>("compileJava") {
  options.release.set(8)
}

// This module should have NO dependencies - it goes into bootstrap classloader
dependencies {
  // No dependencies allowed!

  // Testing dependencies (explicit versions needed since this module is excluded from dependencyManagement)
  testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
  testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.0")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
  testImplementation("org.assertj:assertj-core:3.24.2")
}

tasks {
  jar {
    archiveBaseName.set("serviceevents-bootstrap-bridge")
  }
  test {
    useJUnitPlatform()
  }
}
