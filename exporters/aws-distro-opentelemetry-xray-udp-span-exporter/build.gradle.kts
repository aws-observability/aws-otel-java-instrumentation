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

group = "com.sonatype.central.testing.amazon"
version = "1.0.0"

dependencies {
  implementation(platform("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:2.10.0"))
  implementation("io.opentelemetry:opentelemetry-sdk")
  implementation("io.opentelemetry:opentelemetry-exporter-otlp-common")
  compileOnly("com.google.code.findbugs:jsr305:3.0.2")
  testImplementation("io.opentelemetry:opentelemetry-sdk")
  testImplementation("io.opentelemetry:opentelemetry-exporter-otlp-common")
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

tasks.javadoc {
  options {
    (this as CoreJavadocOptions).addStringOption("Xdoclint:none", "-quiet")
  }
  isFailOnError = false
}

sourceSets {
  main {
    java {
      srcDirs("src/main/java")
    }
  }
  test {
    java {
      srcDirs("src/test/java")
    }
  }
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

nexusPublishing {
  repositories {
    sonatype {
      nexusUrl.set(uri("https://aws.oss.sonatype.org/service/local/"))
      snapshotRepositoryUrl.set(uri("https://aws.oss.sonatype.org/content/repositories/snapshots/"))
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
          name.set("AWS Distro for OpenTelemetry X-Ray UDP Exporter")
          description.set(
            "AWS X-Ray UDP Exporter for OpenTelemetry Java"
          )
          url.set("https:/github.com/aws-observability/aws-otel-java-instrumentation")
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
