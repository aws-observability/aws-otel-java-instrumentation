plugins {
  java

  id("com.diffplug.spotless")
}

base.archivesBaseName = "aws-otel-lambda-java-extensions"
group = "software.amazon.opentelemetry.lambda"

repositories {
  mavenCentral()
  mavenLocal()
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

spotless {
  java {
    googleJavaFormat("1.15.0")
  }
}

val javaagentDependency by configurations.creating {
  extendsFrom()
}

val otelVersion: String by project

dependencies {
  compileOnly(platform("io.opentelemetry:opentelemetry-bom:$otelVersion"))
  compileOnly(platform("io.opentelemetry:opentelemetry-bom-alpha:$otelVersion-alpha"))
  // Already included in wrapper so compileOnly
  compileOnly("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure-spi")
  compileOnly("io.opentelemetry:opentelemetry-sdk-extension-aws")
  javaagentDependency("software.amazon.opentelemetry:aws-opentelemetry-agent:$otelVersion-adot-lambda1")
}

tasks.register<Copy>("download") {
  from(javaagentDependency)
  into("$buildDir/javaagent")
}

tasks.named("build") {
  dependsOn("download")
}
