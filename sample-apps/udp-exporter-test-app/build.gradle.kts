plugins {
  id("java")
  kotlin("jvm") version "1.9.0"
  id("io.spring.dependency-management") version "1.1.0"
  id("org.springframework.boot") version "2.7.17"
}

var xrayUdpSpanExporterVersion = ""
ext {
  xrayUdpSpanExporterVersion = System.getenv("XRAY_UDP_SPAN_EXPORTER_VERSION") ?: ""
}

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("software.amazon.distro.opentelemetry:aws-distro-opentelemetry-xray-udp-span-exporter:${xrayUdpSpanExporterVersion}")
  implementation(platform("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:2.10.0"))
  implementation("io.opentelemetry:opentelemetry-api")
  implementation("io.opentelemetry:opentelemetry-sdk")
}

tasks.bootJar {
  archiveFileName.set("udp-exporter-test-app.jar")
  mainClass.set("com.amazon.sampleapp.ValidationApp")
}
