// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

plugins {
  `java-library`
}

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
  implementation("com.google.code.gson:gson:2.10.1")
  implementation("com.sparkjava:spark-core")
  implementation("com.fasterxml.jackson.core:jackson-databind:2.13.3")
}
