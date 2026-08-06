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
  id("com.google.cloud.tools.jib") version "3.4.0"
  id("com.google.protobuf") version "0.9.4"
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

application {
  mainClass.set("software.amazon.distro.opentelemetry.cloudwatch.spanmetrics.e2e.app.JavaagentApp")
}

dependencies {
  // No OpenTelemetry dependencies: the javaagent (mounted at runtime by the test) instruments
  // this application. Only a JDBC driver so a DB CLIENT span is produced.
  implementation("com.h2database:h2:2.2.224")

  // gRPC: agent grpc instrumentation emits rpc.* spans for the in-process round trip.
  // Workaround for @javax.annotation.Generated in generated stubs.
  implementation("javax.annotation:javax.annotation-api:1.3.2")
  implementation("io.grpc:grpc-api:1.56.1")
  implementation("io.grpc:grpc-protobuf:1.56.1")
  implementation("io.grpc:grpc-stub:1.56.1")
  runtimeOnly("io.grpc:grpc-netty-shaded:1.56.1")

  // Kafka: agent kafka-clients instrumentation emits messaging.* spans.
  implementation("org.apache.kafka:kafka-clients:3.6.1")

  // Embedded Jetty (jakarta.servlet based, 11.x): the javaagent instruments Jetty + servlets, so
  // each endpoint yields a SERVER span named by its route (e.g. "GET /ping") with http.route.
  implementation("org.eclipse.jetty:jetty-server:11.0.20")
  implementation("org.eclipse.jetty:jetty-servlet:11.0.20")
}

protobuf {
  protoc {
    artifact = "com.google.protobuf:protoc:3.24.3"
  }
  plugins {
    create("grpc") {
      artifact = "io.grpc:protoc-gen-grpc-java:1.69.1"
    }
  }
  generateProtoTasks {
    all().forEach {
      it.plugins {
        create("grpc")
      }
    }
  }
}

tasks {
  named("jib") {
    enabled = false
  }
}

jib {
  from {
    image = "public.ecr.aws/docker/library/amazoncorretto:23-alpine"
  }
  to {
    image = "cloudwatch-plugin-otel-javaagent-app"
  }
  container {
    ports = listOf("8080")
  }
}
