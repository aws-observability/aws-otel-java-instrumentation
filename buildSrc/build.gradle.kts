plugins {
  `kotlin-dsl`
}
repositories {
  mavenLocal()
  mavenCentral()
  gradlePluginPortal()
}

dependencies {
  implementation("com.google.cloud.tools:jib-gradle-plugin:3.4.3")
}
