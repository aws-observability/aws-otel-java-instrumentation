/*
 * Copyright Amazon.com, Inc. or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

plugins {
  id("java")
  id("java-library")
  id("maven-publish")
  id("signing")
  id("io.github.gradle-nexus.publish-plugin")
  id("nebula.release")
}

group = "software.amazon.opentelemetry"
version = "1.0.0"

// The core targets Java 8 to match the OTel SDK's minimum. The Spring Boot 3 hook requires
// Java 17, so it lives in a separate source set compiled at 17 and merged into the main jar; it
// only ever runs on JVMs that already have Spring Boot 3 (hence Java 17+).
sourceSets {
  create("springHook") {
    java.setSrcDirs(listOf("src/springHook/java"))
    resources.setSrcDirs(listOf("src/springHook/resources"))
    compileClasspath += sourceSets.main.get().output
  }
  // JMH microbenchmarks for the per-span hot path. Never published in the jar.
  create("jmh") {
    java.setSrcDirs(listOf("src/jmh/java"))
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
  }
}

dependencies {
  compileOnly(platform("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:2.10.0"))
  compileOnly("io.opentelemetry:opentelemetry-sdk")
  compileOnly("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure")
  compileOnly("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure-spi")
  compileOnly("io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api:2.10.0-alpha")
  compileOnly("com.google.code.findbugs:jsr305:3.0.2")

  "springHookCompileOnly"(platform("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:2.10.0"))
  "springHookCompileOnly"("io.opentelemetry:opentelemetry-api")
  "springHookCompileOnly"("org.springframework.boot:spring-boot-autoconfigure:3.3.5")

  "jmhImplementation"(platform("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:2.10.0"))
  "jmhImplementation"("io.opentelemetry:opentelemetry-sdk")
  "jmhImplementation"("io.opentelemetry:opentelemetry-sdk-testing")
  "jmhImplementation"("org.openjdk.jmh:jmh-core:1.37")
  "jmhImplementation"("org.mockito:mockito-core:5.3.1") // only for the onStart no-op span target
  "jmhAnnotationProcessor"("org.openjdk.jmh:jmh-generator-annprocess:1.37")

  testImplementation(platform("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:2.10.0"))
  testImplementation("io.opentelemetry:opentelemetry-sdk")
  testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
  testImplementation("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure")
  testImplementation("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure-spi")
  testImplementation(platform("org.junit:junit-bom:5.9.2"))
  testImplementation("org.junit.jupiter:junit-jupiter-api")
  testImplementation("org.junit.jupiter:junit-jupiter-engine")
  testImplementation("org.mockito:mockito-core:5.3.1")
  testImplementation("org.assertj:assertj-core:3.24.2")
  testImplementation("org.mockito:mockito-junit-jupiter:5.3.1")
}

java {
  withSourcesJar()
  withJavadocJar()
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.named<JavaCompile>("compileSpringHookJava") {
  options.release.set(17)
}

tasks.javadoc {
  options {
    (this as CoreJavadocOptions).addStringOption("Xdoclint:none", "-quiet")
  }
  isFailOnError = false
}


tasks.test {
  useJUnitPlatform()
  testLogging {
    events("passed", "skipped", "failed")
  }
}

tasks.jar {
  manifest {
    attributes(
      "Implementation-Title" to project.name,
      "Implementation-Version" to project.version,
    )
  }
  // Fold the Java 17 Spring hook into the single published jar.
  from(sourceSets["springHook"].output)
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named<Jar>("javadocJar") {
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named<Jar>("sourcesJar") {
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.create("printVersion") {
  doLast {
    println(project.version.toString())
  }
}

// Runs the JMH microbenchmarks for the per-span hot path.
// Usage: ./gradlew jmh   (optionally -Pjmh.args="regex")
tasks.register<JavaExec>("jmh") {
  group = "verification"
  description = "Run JMH microbenchmarks"
  mainClass.set("org.openjdk.jmh.Main")
  classpath = sourceSets["jmh"].runtimeClasspath
  val extraArgs = (project.findProperty("jmh.args") as String?)?.split(" ") ?: emptyList()
  args = extraArgs
}

nexusPublishing {
  repositories {
    sonatype {
      nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
      snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
      username.set(System.getenv("PUBLISH_TOKEN_USERNAME"))
      password.set(System.getenv("PUBLISH_TOKEN_PASSWORD"))
    }
  }
}

plugins.withId("maven-publish") {
  plugins.apply("signing")

  configure<PublishingExtension> {
    publications {
      register<MavenPublication>("maven") {
        from(components["java"])

        pom {
          name.set("CloudWatch Plugin for OpenTelemetry (Span Metrics)")
          description.set(
            "Generates request metrics from spans inside the OpenTelemetry Java SDK"
          )
          url.set("https://github.com/aws-observability/aws-otel-java-instrumentation")
          licenses {
            license {
              name.set("Apache License, Version 2.0")
              url.set("https://aws.amazon.com/apache2.0")
              distribution.set("repo")
            }
          }
          developers {
            developer {
              id.set("amazonwebservices")
              organization.set("Amazon Web Services")
              organizationUrl.set("https://aws.amazon.com")
              roles.add("developer")
            }
          }
          scm {
            connection.set("scm:git:git@github.com:aws-observability/aws-otel-java-instrumentation.git")
            developerConnection.set("scm:git:git@github.com:aws-observability/aws-otel-java-instrumentation.git")
            url.set("https://github.com/aws-observability/aws-otel-java-instrumentation.git")
          }
        }
      }
    }
  }

  tasks.withType<Sign>().configureEach {
    onlyIf { System.getenv("CI") == "true" }
  }

  configure<SigningExtension> {
    val signingKey = System.getenv("GPG_PRIVATE_KEY")
    val signingPassword = System.getenv("GPG_PASSPHRASE")
    useInMemoryPgpKeys(signingKey, signingPassword)
    sign(the<PublishingExtension>().publications["maven"])
  }
}
