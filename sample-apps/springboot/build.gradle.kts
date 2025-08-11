import software.amazon.adot.configureImages

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
}

jib {
  configureImages(
    "public.ecr.aws/aws-otel-test/aws-opentelemetry-java-base:alpha-v2",
    "public.ecr.aws/aws-otel-test/aws-otel-java-springboot:v2",
    rootProject.property("localDocker")!!.equals("true"),
    !rootProject.property("localDocker")!!.equals("true"),
    tags = setOf("latest", "${System.getenv("COMMIT_HASH")}"),
  )
}

tasks {
  named("jib") {
    dependsOn(":otelagent:jib")
  }

  named("jibDockerBuild") {
    dependsOn(":otelagent:jibDockerBuild")
  }
}
