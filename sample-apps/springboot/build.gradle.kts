plugins {
  java
  id("org.springframework.boot")
  id("com.google.cloud.tools.jib")
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
  jcenter()
  mavenCentral()
  maven {
    setUrl("https://oss.jfrog.org/libs-snapshot")
  }
  mavenLocal()
}

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter")
  runtimeOnly("com.github.oshi:oshi-core:5.3.6")
  implementation("com.squareup.okhttp3:okhttp")
  implementation("software.amazon.awssdk:s3")
  implementation("io.opentelemetry.instrumentation:opentelemetry-oshi:0.11.0")
  implementation("io.opentelemetry:opentelemetry-api")
}

jib {
  to {
    image = "${System.getenv("AWS_REGISTRY_ACCOUNT")}.dkr.ecr.us-west-2.amazonaws.com/otel-test/springboot:${System.getenv("COMMIT_HASH")}"
  }
  from {
    image = "ghcr.io/anuraaga/aws-opentelemetry-java-base:alpha"
  }
}

tasks {
  named("jib") {
    dependsOn(":otelagent:jib")
  }
}
