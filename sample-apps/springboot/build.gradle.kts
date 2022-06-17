plugins {
  java
  id("org.springframework.boot")
  id("com.google.cloud.tools.jib")
  id("java-library")
}

dependencies {
  api(platform("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom-alpha:1.5.0-alpha"))

  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter")
  implementation("com.squareup.okhttp3:okhttp")
  implementation("software.amazon.awssdk:s3")
  implementation("software.amazon.awssdk:sts")
  implementation("io.opentelemetry:opentelemetry-sdk")
  implementation("io.opentelemetry:opentelemetry-api")
  implementation("io.opentelemetry:opentelemetry-exporter-otlp")
  implementation("io.opentelemetry.contrib:opentelemetry-aws-xray:1.6.0")
  implementation("io.opentelemetry:opentelemetry-extension-trace-propagators")
  implementation("io.opentelemetry:opentelemetry-extension-aws")
  implementation("io.opentelemetry.instrumentation:opentelemetry-aws-sdk-2.2")
}

jib {
  to {
    image = "public.ecr.aws/aws-otel-test/aws-otel-java-springboot"
    tags = setOf("latest", "${System.getenv("COMMIT_HASH")}")
  }
  from {
    image = "public.ecr.aws/aws-otel-test/aws-opentelemetry-java-base:alpha"
    platforms {
      platform {
        architecture = "amd64"
        os = "linux"
      }
      platform {
        architecture = "arm64"
        os = "linux"
      }
    }
  }
}

tasks {
  named("jib") {
    dependsOn(":otelagent:jib")
  }
}
