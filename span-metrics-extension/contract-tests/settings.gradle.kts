rootProject.name = "aws-otel-span-metrics-extension-contract-tests"

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
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

include("mock-collector")
include("apps:javaagent-app")
include("apps:spring-app")
include("apps:autoconfigure-app")
include("apps:manual-app")
