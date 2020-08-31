plugins {
  application
  java
  id("com.google.cloud.tools.jib") version "2.5.0"
}

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
  implementation("com.linecorp.armeria:armeria-grpc")
  implementation("io.opentelemetry:opentelemetry-proto")
  implementation("org.curioswitch.curiostack:protobuf-jackson")
  implementation("org.slf4j:slf4j-simple")
}

jib {
  to {
    image = "docker.pkg.github.com/anuraaga/aws-opentelemetry-java-instrumentation/smoke-tests-fake-backend:master"
  }
  from {
    image = "docker.pkg.github.com/anuraaga/aws-opentelemetry-java-instrumentation/amazoncorretto-slim:master"
  }
}
