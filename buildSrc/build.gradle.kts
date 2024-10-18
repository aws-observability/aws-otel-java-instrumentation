plugins {
  `kotlin-dsl`
}
repositories {
  mavenCentral()
  gradlePluginPortal()
  mavenLocal()
}

dependencies {
  implementation("com.google.cloud.tools:jib-gradle-plugin:3.4.3")
}
