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
  implementation("com.amazonaws:aws-java-sdk-s3")
  implementation("com.sparkjava:spark-core")
  implementation("com.squareup.okhttp3:okhttp")
  implementation("io.opentelemetry:opentelemetry-api")
  implementation("io.opentelemetry:opentelemetry-api-metrics")
}

application {
  mainClass.set("com.amazon.sampleapp.App")
}

jib {
  to {
    image = "public.ecr.aws/aws-otel-test/aws-otel-java-spark-awssdkv1:${System.getenv("COMMIT_HASH")}"
  }
  from {
    image = "public.ecr.aws/aws-otel-test/aws-opentelemetry-java-base:alpha"
  }
}

tasks {
  named("jib") {
    dependsOn(":otelagent:jib")
  }
}
