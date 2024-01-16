import com.google.cloud.tools.jib.gradle.BuildImageTask
import com.google.cloud.tools.jib.gradle.JibExtension
import software.amazon.adot.configureImages

plugins {
  java

  application
  id("com.google.cloud.tools.jib")
}

dependencies {
  implementation("commons-logging:commons-logging")
  implementation("com.sparkjava:spark-core")
  implementation("com.squareup.okhttp3:okhttp")
  implementation("io.opentelemetry:opentelemetry-api")
  implementation("org.apache.logging.log4j:log4j-core")
  implementation("software.amazon.awssdk:s3")
  implementation("software.amazon.awssdk:sts")

  runtimeOnly("org.apache.logging.log4j:log4j-slf4j-impl")
}

application {
  mainClass.set("com.amazon.sampleapp.App")
}

jib {

  configureImages(
    "public.ecr.aws/aws-otel-test/aws-opentelemetry-java-base:alpha",
    "public.ecr.aws/aws-otel-test/aws-otel-java-spark",
    localDocker = rootProject.property("localDocker")!!.equals("true"),
    multiPlatform = !rootProject.property("localDocker")!!.equals("true"),
    tags = setOf("latest", "${System.getenv("COMMIT_HASH")}"),
  )

  container {
    appRoot = "/aws-observability"
    environment = mapOf(
      "OTEL_LOGS_EXPORTER" to "otlp",
    )
  }
}

tasks {
  named("jib") {
    dependsOn(":otelagent:jib")
  }
  named("jibDockerBuild") {
    dependsOn(":otelagent:jibDockerBuild")
  }
  register<BuildImageTask>("jibBuildWithoutAgent") {
    dependsOn(":sample-apps:spark:build")
    val j = JibExtension(project)
    j.configureImages(
      "eclipse-temurin:17",
      "public.ecr.aws/aws-otel-test/aws-otel-java-spark-without-auto-instrumentation-agent",
      localDocker = false,
      multiPlatform = !rootProject.property("localDocker")!!.equals("true"),
      tags = setOf("latest", "${System.getenv("COMMIT_HASH")}"),
    )
    setJibExtension(j)
  }
}
