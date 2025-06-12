rootProject.name = "aws-distro-opentelemetry-xray-udp-span-exporter"

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    mavenLocal()

    maven {
      setUrl("https://oss.sonatype.org/content/repositories/snapshots")
    }
  }
}

pluginManagement {
  plugins {
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
    id("nebula.release") version "18.0.6"
  }
}
