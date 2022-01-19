plugins {
  java
  id("org.springframework.boot")
  id("com.google.cloud.tools.jib")
}

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter")
  implementation("com.squareup.okhttp3:okhttp")
  implementation("software.amazon.awssdk:s3")
  implementation("software.amazon.awssdk:sts")
  implementation("io.opentelemetry:opentelemetry-api")
  implementation("io.opentelemetry:opentelemetry-api-metrics")
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
