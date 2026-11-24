rootProject.name = "cloudwatch-plugin-otel"

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    mavenLocal()

    maven {
      setUrl("https://central.sonatype.com/repository/maven-snapshots/")
    }
  }
}

pluginManagement {
  plugins {
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
    id("nebula.release") version "18.0.6"
    id("com.diffplug.spotless") version "7.0.3"
  }
}

// Lets Gradle auto-provision the Java 8 toolchain the smoke test runs on, without a preinstalled JDK.
plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
