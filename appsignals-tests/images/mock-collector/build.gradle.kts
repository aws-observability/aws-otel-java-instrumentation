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
  java
  id("com.google.cloud.tools.jib")
}

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
  implementation("com.linecorp.armeria:armeria-grpc")
  // Bumped to 1.10.0-alpha (from the shared platform's 1.0.0-alpha) for the
  // profiles.v1development.* schema — specifically ExportProfilesServiceRequest, captured over HTTP
  // by MockCollectorProfilesService. 1.10.0-alpha still carries the trace/logs/metrics v1 packages
  // (and gRPC service stubs) the existing trace/metrics/logs services use, so they are unaffected.
  // protobuf-java is bumped to 4.34.0 to match the 1.10.0-alpha gencode (overrides the shared
  // protobuf-bom 3.25.1 constraint); scoped to this module only.
  implementation("io.opentelemetry.proto:opentelemetry-proto:1.10.0-alpha")
  implementation("com.google.protobuf:protobuf-java:4.34.0")
  // protobuf-jackson 2.2.0 (the shared platform pin) is protobuf-java 3.x only — its
  // ProtoFieldInfo.isInOneof calls Descriptors.FieldDescriptor.hasOptionalKeyword(), which is
  // inaccessible in protobuf-java 4.x (IllegalAccessError at marshaller-build time). 2.8.1 targets
  // protobuf-java 4.x, so it works with the 4.34.0 runtime the profiles gencode requires. Scoped to
  // this module (overrides the platform 2.2.0); the smoke-tests stay on 2.2.0 + protobuf-java 3.x.
  implementation("org.curioswitch.curiostack:protobuf-jackson:2.8.1")
  implementation("org.slf4j:slf4j-simple")
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
    "public.ecr.aws/docker/library/amazoncorretto:23-alpine",
    "aws-appsignals-mock-collector",
    localDocker = rootProject.property("localDocker")!! == "true",
    multiPlatform = false,
  )
}
