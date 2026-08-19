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
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

// The plain upstream OpenTelemetry javaagent, resolved from Maven Central. It is mounted into the
// javaagent-app container at test time. This is NOT ADOT's agent.
//
// -PotelAgentVersion pins the agent release so CI can run the javaagent mode across the supported
// range. The agent embeds its own SDK, so this exercises the plugin against the SDK each agent
// release ships (not the raw SDK floor, which the manual/autoconfigure modes cover directly).
// "latest" tracks the newest release so upstream changes surface.
val otelAgentVersion = (project.findProperty("otelAgentVersion") as String?) ?: "2.29.0"
val javaagent by configurations.creating {
  // Always re-resolve "latest.release" so a nightly run picks up new agent releases.
  resolutionStrategy.cacheDynamicVersionsFor(0, "seconds")
}

dependencies {
  if (otelAgentVersion == "latest") {
    javaagent("io.opentelemetry.javaagent:opentelemetry-javaagent:latest.release")
  } else {
    javaagent("io.opentelemetry.javaagent:opentelemetry-javaagent:$otelAgentVersion")
  }

  testImplementation(platform("com.linecorp.armeria:armeria-bom:1.26.4"))
  testImplementation(platform("io.grpc:grpc-bom:1.59.1"))
  testImplementation(platform("com.google.guava:guava-bom:33.0.0-jre"))
  testImplementation(platform("com.fasterxml.jackson:jackson-bom:2.21.4"))
  testImplementation(platform("org.testcontainers:testcontainers-bom:1.19.3"))
  testImplementation(platform("org.junit:junit-bom:5.10.1"))

  testImplementation("com.google.guava:guava")
  testImplementation("com.linecorp.armeria:armeria")
  testImplementation("com.linecorp.armeria:armeria-grpc")
  testImplementation("io.opentelemetry:opentelemetry-api:1.45.0")
  testImplementation("io.opentelemetry.proto:opentelemetry-proto:1.0.0-alpha")
  testImplementation("org.curioswitch.curiostack:protobuf-jackson:2.2.0")
  testImplementation("com.fasterxml.jackson.core:jackson-databind")
  testImplementation("org.slf4j:slf4j-simple:1.7.36")
  testImplementation("org.testcontainers:testcontainers")
  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("org.testcontainers:kafka")
  testImplementation("org.junit.jupiter:junit-jupiter-api")
  testImplementation("org.junit.jupiter:junit-jupiter-engine")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testImplementation("org.assertj:assertj-core:3.24.2")
}

// The span-metrics extension jar built by the sibling module. Passed to tests (and mounted into the
// javaagent-app container) as a system property. Resolved by pattern so it is not pinned to a
// specific version (excludes the -sources/-javadoc classifier jars).
val extensionJar =
    fileTree("../build/libs") { include("cloudwatch-plugin-otel-*.jar")
      exclude("*-sources.jar", "*-javadoc.jar") }.singleFile

tasks {
  // Disable the default test task from the java plugin; contract tests run via the contractTests
  // task.
  named("test") {
    enabled = false
  }

  register<Test>("contractTests") {
    useJUnitPlatform()

    // Build all container images before the tests run.
    dependsOn(":mock-collector:jibDockerBuild")
    dependsOn(":apps:javaagent-app:jibDockerBuild")
    dependsOn(":apps:spring-app:jibDockerBuild")
    dependsOn(":apps:autoconfigure-app:jibDockerBuild")
    dependsOn(":apps:manual-app:jibDockerBuild")

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath

    // Resolve the plain OpenTelemetry javaagent jar and hand its path to the tests. The javaagent
    // subclass mounts this into the app container.
    val agentJarPath = javaagent.singleFile.absolutePath
    systemProperty("spanmetrics.javaagent.jar.path", agentJarPath)
    systemProperty("spanmetrics.extension.jar.path", extensionJar.absolutePath)

    testLogging {
      events("passed", "skipped", "failed")
    }

    // Forward Docker-related env vars to the test JVM for Testcontainers.
    listOf("DOCKER_HOST", "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "TESTCONTAINERS_RYUK_DISABLED")
      .forEach { envVar ->
        System.getenv(envVar)?.let { environment(envVar, it) }
      }
    System.getenv("DOCKER_API_VERSION")?.let { systemProperty("api.version", it) }

    // On macOS (Docker Desktop), set defaults for Testcontainers compatibility.
    if (System.getProperty("os.name").lowercase().contains("mac")) {
      if (System.getenv("DOCKER_HOST") == null) {
        environment("DOCKER_HOST", "unix:///var/run/docker.sock")
      }
      if (System.getenv("DOCKER_API_VERSION") == null) {
        environment("DOCKER_API_VERSION", "1.44")
        systemProperty("api.version", "1.44")
      }
    }
  }
}
