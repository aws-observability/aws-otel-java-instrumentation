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
  id("com.diffplug.spotless")
  jacoco
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
  // Drives the core hot path on a Java 8 runtime (see java8SmokeTest task). Never published.
  create("smokeTest") {
    java.setSrcDirs(listOf("src/smokeTest/java"))
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

  "smokeTestImplementation"(platform("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:2.10.0"))
  "smokeTestImplementation"("io.opentelemetry:opentelemetry-sdk")
  "smokeTestImplementation"("io.opentelemetry:opentelemetry-sdk-testing")

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

// -PotelTestVersion=<x.y.z|latest> pins the core OpenTelemetry SDK on the test classpath, so CI can
// run the unit tests at the minimum supported version (1.32.0) and, with "latest", against the
// newest release (matching OTel's own min + testLatestDeps convention). The -alpha satellite
// artifacts track a separate version string and are left to resolve transitively.
val otelTestVersion = project.findProperty("otelTestVersion") as String?
if (otelTestVersion != null) {
  val resolvedVersion = if (otelTestVersion == "latest") "latest.release" else otelTestVersion
  configurations.matching { it.name.startsWith("test") }.configureEach {
    resolutionStrategy {
      cacheDynamicVersionsFor(0, "seconds") // always re-resolve "latest" so CI catches new releases
      eachDependency {
        if (requested.group == "io.opentelemetry" &&
          !requested.name.contains("bom") &&
          !requested.name.endsWith("-incubator") &&
          requested.name != "opentelemetry-api-events"
        ) {
          useVersion(resolvedVersion)
        }
      }
    }
  }
}

java {
  withSourcesJar()
  withJavadocJar()
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

// release=8 rejects any Java 9+ API at compile time, so the core stays runnable on Java 8.
tasks.named<JavaCompile>("compileJava") {
  options.release.set(8)
}

tasks.named<JavaCompile>("compileSpringHookJava") {
  options.release.set(17)
}

tasks.named<JavaCompile>("compileSmokeTestJava") {
  options.release.set(8)
}

// Runs the core hot path on a real Java 8 JVM so a Java 9+ API that escaped the compile gate fails
// here. Requires a Java 8 toolchain (auto-provisioned by Gradle, or from an installed JDK 8).
tasks.register<JavaExec>("java8SmokeTest") {
  group = "verification"
  description = "Run the plugin's core hot path on a Java 8 runtime"
  javaLauncher.set(
    javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(8)) },
  )
  classpath = sourceSets["smokeTest"].runtimeClasspath
  mainClass.set(
    "software.amazon.opentelemetry.cloudwatch.spanmetrics.Java8SmokeTest",
  )
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
  finalizedBy(tasks.named("jacocoTestReport"))
}

// Same formatting, license-header, and coverage conventions as the parent repo, applied here since
// this is a standalone build not included by the root settings.
private val licenseHeader = "${rootProject.projectDir}/../config/license/header.java"

spotless {
  java {
    googleJavaFormat()
    licenseHeaderFile(licenseHeader)
  }
  kotlinGradle {
    ktlint("1.4.0").editorConfigOverride(mapOf("indent_size" to "2", "continuation_indent_size" to "2"))
    targetExclude("settings.gradle.kts")
    licenseHeaderFile(licenseHeader, "plugins|include|import|rootProject")
  }
}

tasks.named("check") {
  dependsOn(tasks.named("spotlessCheck"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
  dependsOn(tasks.test)
  reports {
    xml.required.set(true)
    html.required.set(true)
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
            "Generates request metrics from spans inside the OpenTelemetry Java SDK",
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
