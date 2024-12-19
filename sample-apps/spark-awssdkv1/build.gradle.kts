import software.amazon.adot.configureImages

plugins {
  java

  application
  id("com.google.cloud.tools.jib")
}

dependencies {
  implementation("commons-logging:commons-logging")
  implementation("com.amazonaws:aws-java-sdk-s3")
  implementation("com.amazonaws:aws-java-sdk-sts")
  implementation("com.sparkjava:spark-core")
  implementation("com.squareup.okhttp3:okhttp")
  implementation("io.opentelemetry:opentelemetry-api")
}

application {
  mainClass.set("com.amazon.sampleapp.App")
}

jib {
  configureImages(
    "public.ecr.aws/aws-otel-test/aws-opentelemetry-java-base:alpha-v2",
    "public.ecr.aws/aws-otel-test/aws-otel-java-spark-awssdkv1:v2",
    localDocker = rootProject.property("localDocker")!!.equals("true"),
    multiPlatform = !rootProject.property("localDocker")!!.equals("true"),
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
