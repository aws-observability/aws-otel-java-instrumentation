plugins {
  id("java")
  kotlin("jvm")
  id("io.spring.dependency-management") version "1.1.0"
  id("org.springframework.boot") version "2.7.17"
}

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("software.amazon.opentelemetry.exporters.otlp.udp.trace:aws-otel-otlp-udp-exporter:0.0.1")
  implementation("io.opentelemetry:opentelemetry-api")
  implementation("io.opentelemetry:opentelemetry-sdk")
}

tasks.bootJar {
  archiveFileName.set("integ-test-app.jar")
  mainClass.set("com.amazon.sampleapp.ValidationApp")
}
