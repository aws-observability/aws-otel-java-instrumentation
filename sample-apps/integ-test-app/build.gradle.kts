plugins {
  id("java")
  kotlin("jvm") version "1.9.0"
  id("io.spring.dependency-management") version "1.1.0"
  id("org.springframework.boot") version "2.7.17"
}

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("software.amazon.distro.opentelemetry.exporter.xray.lambda:aws-opentelemetry-xray-lambda-exporter:0.1.0")
  implementation(platform("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:2.10.0"))
  implementation("io.opentelemetry:opentelemetry-api")
  implementation("io.opentelemetry:opentelemetry-sdk")
  implementation("io.opentelemetry:opentelemetry-exporter-otlp")
}

tasks.bootJar {
  archiveFileName.set("integ-test-app.jar")
  mainClass.set("com.amazon.sampleapp.ValidationApp")
}
