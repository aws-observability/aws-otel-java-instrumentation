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
  implementation("io.opentelemetry.proto:opentelemetry-proto")
  implementation("org.curioswitch.curiostack:protobuf-jackson")
  implementation("org.slf4j:slf4j-simple")
}

jib {
  to {
    image = "public.ecr.aws/aws-otel-test/aws-otel-java-test-fakebackend:alpha-v2"
  }
  from {
    image = "gcr.io/distroless/java17-debian11:debug"
  }
}
