plugins {
  java

  application
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
  implementation("commons-logging:commons-logging")
  implementation("com.sparkjava:spark-core")
  implementation("com.squareup.okhttp3:okhttp")
  implementation("io.opentelemetry:opentelemetry-api")
  implementation("io.opentelemetry:opentelemetry-api-metrics")
  implementation("software.amazon.awssdk:s3")
}

application {
  mainClass.set("com.amazon.sampleapp.App")
}

jib {
  to {
    image = "${System.getenv("AWS_REGISTRY_ACCOUNT")}.dkr.ecr.us-west-2.amazonaws.com/otel-test/spark:${System.getenv("COMMIT_HASH")}"
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
