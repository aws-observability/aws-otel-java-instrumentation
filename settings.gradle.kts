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
    id("com.diffplug.spotless") version "6.25.0"
    id("com.github.ben-manes.versions") version "0.50.0"
    id("com.github.jk1.dependency-license-report") version "2.5"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("com.google.cloud.tools.jib") version "3.4.3"
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
    id("nebula.release") version "18.0.6"
    id("org.springframework.boot") version "2.7.17"
    id("org.owasp.dependencycheck") version "8.4.0"
  }
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    mavenLocal()

    maven {
      setUrl("https://oss.sonatype.org/content/repositories/snapshots")
    }
  }
}

include(":awsagentprovider")
include(":awspropagator")
include(":dependencyManagement")
include(":instrumentation:logback-1.0")
include(":instrumentation:log4j-2.13.2")
include(":instrumentation:jmx-metrics")
include(":otelagent")
include(":smoke-tests:fakebackend")
include(":smoke-tests:runner")
include(":smoke-tests:spring-boot")
include(":sample-apps:springboot")
include(":sample-apps:spark")
include(":sample-apps:spark-awssdkv1")
include(":sample-apps:apigateway-lambda")

// Used for contract tests
include("appsignals-tests:images:mock-collector")
include("appsignals-tests:images:http-servers:spring-mvc")
include("appsignals-tests:images:http-servers:tomcat")
include("appsignals-tests:images:http-servers:netty-server")
include("appsignals-tests:contract-tests")
include("appsignals-tests:images:http-clients:native-http-client")
include("appsignals-tests:images:http-clients:spring-mvc-client")
include("appsignals-tests:images:http-clients:apache-http-client")
include("appsignals-tests:images:http-clients:netty-http-client")
//include("appsignals-tests:images:aws-sdk:aws-sdk-base")
//include("appsignals-tests:images:aws-sdk:aws-sdk-v1")
//include("appsignals-tests:images:aws-sdk:aws-sdk-v2")
include("appsignals-tests:images:grpc:grpc-base")
include("appsignals-tests:images:grpc:grpc-server")
include("appsignals-tests:images:grpc:grpc-client")
include("appsignals-tests:images:jdbc")
include("appsignals-tests:images:kafka:kafka-producers")
include("appsignals-tests:images:kafka:kafka-consumers")
