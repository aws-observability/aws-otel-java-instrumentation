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
  id("com.gradleup.shadow")
}

base.archivesBaseName = "aws-instrumentation-serviceevents"

// ServiceEvents runs on Java 8+. The root build only sets source/targetCompatibility = 8, which
// controls the emitted bytecode version but NOT the API surface — Java 9+ JDK API references (e.g.
// ProcessHandle) compile fine and then throw NoClassDefFoundError at runtime on Java 8, aborting the
// whole agent. Pinning the release flag makes the compiler reject Java 9+ JDK APIs at build time.
tasks.named<JavaCompile>("compileJava") {
  options.release.set(8)
}

dependencies {
  // Bootstrap bridge for cross-classloader communication (compile-only since it's loaded via bootstrap)
  compileOnly(project(":serviceevents-bootstrap-bridge"))

  // OpenTelemetry dependencies
  compileOnly("io.opentelemetry:opentelemetry-api")
  compileOnly("io.opentelemetry:opentelemetry-sdk")
  compileOnly("io.opentelemetry:opentelemetry-sdk-trace")
  compileOnly("io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api")
  compileOnly("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api")
  compileOnly("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure-spi")
  compileOnly("net.bytebuddy:byte-buddy")

  // OTLP exporters for ServiceEvents telemetry signals
  compileOnly("io.opentelemetry:opentelemetry-exporter-otlp")
  // opentelemetry-exporter-otlp-common provides MetricsRequestMarshaler, used by the
  // local-file metric exporter to write canonical OTLP/JSON (same serializer the OTLP HTTP
  // exporter uses). compileOnly: it's already on the agent runtime classpath via
  // OtlpHttpMetricExporter (network mode), so no new runtime dependency is shipped.
  compileOnly("io.opentelemetry:opentelemetry-exporter-otlp-common")

  // AWS agent provider (for SigV4 log exporter and AwsSpanProcessingUtil)
  compileOnly(project(":awsagentprovider"))

  // JSON processing
  implementation("com.fasterxml.jackson.core:jackson-databind:2.16.0")
  implementation("com.fasterxml.jackson.core:jackson-core:2.16.0")
  implementation("com.fasterxml.jackson.core:jackson-annotations:2.16.0")

  // Servlet API for web framework instrumentation
  compileOnly("javax.servlet:javax.servlet-api:4.0.1")

  // Testing dependencies
  testImplementation(project(":serviceevents-bootstrap-bridge"))
  testImplementation(project(":awsagentprovider"))
  testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
  testImplementation("org.mockito:mockito-core:5.7.0")
  testImplementation("io.opentelemetry:opentelemetry-api")
  testImplementation("io.opentelemetry:opentelemetry-sdk")
  testImplementation("io.opentelemetry:opentelemetry-sdk-trace")
  testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
  // Needed by ServiceEventsInstrumentationModuleTest to evaluate the ByteBuddy scope matcher.
  // The main sources reference these as compileOnly (provided by the agent at runtime); tests
  // that exercise the matcher directly need them on the test classpath.
  testImplementation("net.bytebuddy:byte-buddy")
  testImplementation("io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api")
  // The metric file exporter uses MetricsRequestMarshaler from opentelemetry-exporter-otlp-common
  // (compileOnly in main; the agent provides it at runtime via OtlpHttpMetricExporter). Tests that
  // exercise the file exporter need it on the test classpath.
  testImplementation("io.opentelemetry:opentelemetry-exporter-otlp-common")
}

// Generate serviceevents-version.properties at build time so the SDK version
// is available on the classpath at runtime (read by DeploymentEventCollector).
val generateVersionProperties = tasks.register("generateVersionProperties") {
  val outputDir = layout.buildDirectory.dir("generated/resources/serviceevents")
  outputs.dir(outputDir)
  doLast {
    val propsFile = outputDir.get().file("serviceevents-version.properties").asFile
    propsFile.parentFile.mkdirs()
    propsFile.writeText("sdk_version=${project.version}\n")
  }
}

sourceSets.main {
  resources.srcDir(generateVersionProperties.map { layout.buildDirectory.dir("generated/resources/serviceevents") })
}

tasks.named("processResources") {
  dependsOn(generateVersionProperties)
}

tasks.test {
  useJUnitPlatform()
}
