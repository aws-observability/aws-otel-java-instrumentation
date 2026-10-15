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
// The profiler's JFR scan reads recordings with async-profiler's own pure-Java parser
// (one.jfr.JfrReader, from tools.profiler:jfr-converter — Java-8 bytecode, no jdk.jfr.consumer
// dependency), so the module compiles cleanly at --release 8.
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

  // Async-profiler Java API (4.x) for CPU/wall-clock profiling
  implementation("tools.profiler:async-profiler:4.5")

  // async-profiler's own pure-Java JFR parser (one.jfr.JfrReader + one.jfr.event.*). Java-8
  // bytecode with no jdk.jfr.consumer dependency, so RotationBoundaryProcessor can scan JFR
  // recordings while the module compiles at --release 8 (runs on Java 8+).
  implementation("tools.profiler:jfr-converter:4.5")

  // Native OTLP profiles signal: the ExportProfilesServiceRequest protobuf. The explicit
  // 1.10.0-alpha version deliberately overrides the managed opentelemetry-proto (1.0.0-alpha)
  // constraint; scoped to this module so the contract-tests / mock-collector on proto 1.0.0-alpha
  // are undisturbed. 1.10.0-alpha carries the profiles.v1development.* schema.
  //
  // protobuf-java is compileOnly, NOT bundled: the merged upstream opentelemetry-javaagent already
  // ships protobuf-java 4.x at inst/com/google/protobuf/, and our generated proto classes are
  // gencode 4.34.0, which runs on any same-major runtime >= 4.34.0 (protobuf's gencode/runtime
  // compatibility rule). Bundling our own copy would collide (two inst/com/google/
  // protobuf/*). So we exclude the transitive protobuf-java from opentelemetry-proto and declare it
  // compileOnly (same pattern as okhttp), letting the agent's copy satisfy it at runtime. The
  // explicit 4.34.0 also overrides the shared protobuf-bom (3.25.1) on the compile classpath.
  implementation("io.opentelemetry.proto:opentelemetry-proto:1.10.0-alpha") {
    exclude(group = "com.google.protobuf", module = "protobuf-java")
  }
  compileOnly("com.google.protobuf:protobuf-java:4.34.0")

  // OkHttp for the native OTLP profiles HTTP exporter. compileOnly: okhttp is already on the agent
  // runtime classpath (awsagentprovider ships it as a managed `implementation`), so no new runtime
  // dependency is shipped.
  compileOnly("com.squareup.okhttp3:okhttp")

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
  // ServiceEventsTracerCustomizerProviderTest exercises the SPI customizer's registration gate.
  // autoconfigure-spi is compileOnly in main (provided by the agent at runtime); tests that invoke
  // customize(AutoConfigurationCustomizer) need it on the test classpath.
  testImplementation("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure-spi")
  // Needed by ServiceEventsInstrumentationModuleTest to evaluate the ByteBuddy scope matcher.
  // The main sources reference these as compileOnly (provided by the agent at runtime); tests
  // that exercise the matcher directly need them on the test classpath.
  testImplementation("net.bytebuddy:byte-buddy")
  testImplementation("io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api")
  // The metric file exporter uses MetricsRequestMarshaler from opentelemetry-exporter-otlp-common
  // (compileOnly in main; the agent provides it at runtime via OtlpHttpMetricExporter). Tests that
  // exercise the file exporter need it on the test classpath.
  testImplementation("io.opentelemetry:opentelemetry-exporter-otlp-common")
  // OtlpHttpProfilesExporterTest drives the exporter (okhttp, compileOnly in main) against a
  // MockWebServer and parses the captured body back into an ExportProfilesServiceRequest.
  // protobuf-java is compileOnly in main (provided by the agent at runtime), so tests need it
  // explicitly at 4.34.0 to match opentelemetry-proto's gencode and override the protobuf-bom
  // (3.25.1). Used by OtlpProfileBuilderProtoTest + OtlpHttpProfilesExporterTest.
  testImplementation("com.google.protobuf:protobuf-java:4.34.0")
  testImplementation("com.squareup.okhttp3:okhttp")
  // okhttp resolves to 5.x on the runtime/test classpath (pulled by the aws-xray/aws-resources
  // contrib libs), so the exporter test uses the matching okhttp-5.x MockWebServer (mockwebserver3
  // package). The legacy okhttp3.mockwebserver artifact is pinned to 4.12.0 elsewhere and is
  // binary-incompatible with okhttp 5.x.
  testImplementation("com.squareup.okhttp3:mockwebserver3:5.4.0")
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
