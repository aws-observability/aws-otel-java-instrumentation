plugins {
  java
  application
}

application {
  mainClass.set("com.amazon.sampleapp.LambdaHandler")
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}

dependencies {
  implementation("com.amazonaws:aws-lambda-java-core:1.2.2")
  implementation("software.amazon.awssdk:s3:2.29.23")
  implementation("org.json:json:20240303")
  implementation("org.slf4j:jcl-over-slf4j:2.0.16")
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
}

tasks.jar {
  manifest {
    attributes["Main-Class"] = "com.amazon.sampleapp.LambdaHandler"
  }
}

tasks.register<Zip>("createLambdaZip") {
  dependsOn("build")
  from(tasks.compileJava.get())
  from(tasks.processResources.get())
  into("lib") {
    from(configurations.runtimeClasspath.get())
  }
  archiveFileName.set("lambda-function.zip")
  destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}
