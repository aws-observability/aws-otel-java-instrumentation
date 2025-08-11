pluginManagement {
  plugins {
    id("com.diffplug.spotless") version "6.13.0"
    id("com.github.ben-manes.versions") version "0.50.0"
    id("com.gradleup.shadow") version "8.3.5"
  }
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    mavenLocal()
  }
}

rootProject.name = "aws-otel-lambda-java"
