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

import com.github.jk1.license.render.InventoryMarkdownReportRenderer
import nebula.plugin.release.git.opinion.Strategies
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
  java

  id("com.diffplug.spotless")
  id("com.github.ben-manes.versions")
  id("com.github.jk1.dependency-license-report")
  id("nebula.release")
}

release {
  defaultVersionStrategy = Strategies.getSNAPSHOT()
}

val releaseTask = tasks.named("release")
val postReleaseTask = tasks.named("release")

allprojects {
  repositories {
    jcenter()
    mavenCentral()
    mavenLocal()

    maven {
      setUrl("https://dl.bintray.com/open-telemetry/maven")
    }

    maven {
      setUrl("https://oss.jfrog.org/libs-snapshot")
    }
  }

  project.group = "software.amazon.opentelemetry"

  plugins.apply("com.diffplug.spotless")

  plugins.withType(BasePlugin::class) {
    val assemble = tasks.named("assemble")
    val check = tasks.named("check")

    releaseTask.configure {
      dependsOn(assemble, check)
    }
  }

  spotless {
    kotlinGradle {
      ktlint("0.40.0").userData(mapOf("indent_size" to "2", "continuation_indent_size" to "2"))

      // Doesn't support pluginManagement block
      targetExclude("settings.gradle.kts")

      if (!project.path.startsWith(":sample-apps:")) {
        licenseHeaderFile("${rootProject.projectDir}/config/license/header.java", "plugins|include|import")
      }
    }
  }

  plugins.withId("java") {
    java {
      sourceCompatibility = JavaVersion.VERSION_1_8
      targetCompatibility = JavaVersion.VERSION_1_8
    }

    dependencies {
      configurations.configureEach {
        if (name.endsWith("Classpath")) {
          add(name, enforcedPlatform(project(":dependencyManagement")))
        }
      }

      testImplementation("org.assertj:assertj-core")
      testImplementation("org.junit.jupiter:junit-jupiter-api")
      testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    }

    spotless {
      java {
        googleJavaFormat("1.8")

        if (!project.path.startsWith(":sample-apps:")) {
          licenseHeaderFile("${rootProject.projectDir}/config/license/header.java")
        }
      }
    }

    val enableCoverage: String? by project
    if (enableCoverage == "true") {
      plugins.apply("jacoco")

      tasks {
        val build by named("build")
        withType<JacocoReport> {
          build.dependsOn(this)

          reports {
            xml.isEnabled = true
            html.isEnabled = true
            csv.isEnabled = false
          }
        }
      }
    }

    tasks {
      withType<Test> {
        useJUnitPlatform()

        testLogging {
          exceptionFormat = TestExceptionFormat.FULL
          showStackTraces = true
        }
      }

      named<JavaCompile>("compileTestJava") {
        sourceCompatibility = JavaVersion.VERSION_11.toString()
        targetCompatibility = JavaVersion.VERSION_11.toString()
      }
    }
  }

  plugins.withId("maven-publish") {
    plugins.apply("signing")

    val publishTask = tasks.named("publish")

    postReleaseTask.configure {
      dependsOn(publishTask)
    }

    configure<PublishingExtension> {
      publications {
        register<MavenPublication>("maven") {
          afterEvaluate {
            artifactId = project.findProperty("archivesBaseName") as String
          }

          plugins.withId("java-platform") {
            from(components["javaPlatform"])
          }
          plugins.withId("java-library") {
            from(components["java"])
          }
          plugins.withId("java") {
            from(components["java"])
          }

          versionMapping {
            allVariants {
              fromResolutionResult()
            }
          }

          pom {
            name.set("AWS Distro for OpenTelemetry Java Agent")
            description.set(
              "The Amazon Web Services distribution of the OpenTelemetry Java Instrumentation."
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

      val isSnapshot = version.toString().endsWith("SNAPSHOT")

      repositories {
        maven {
          name = "Sonatype"
          url = uri(
            if (isSnapshot) "https://aws.oss.sonatype.org/content/repositories/snapshots/"
            else "https://aws.oss.sonatype.org/service/local/staging/deploy/maven2"
          )
          credentials {
            username = System.getenv("PUBLISH_USERNAME")
            password = System.getenv("PUBLISH_PASSWORD")
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
}

tasks {
  named<Wrapper>("wrapper") {
    gradleVersion = "6.7.1"
    distributionSha256Sum = "3239b5ed86c3838a37d983ac100573f64c1f3fd8e1eb6c89fa5f9529b5ec091d"
  }

  val cleanLicenseReport by registering(Delete::class) {
    delete("licenses")
  }

  named("generateLicenseReport") {
    dependsOn(cleanLicenseReport)
  }
}

licenseReport {
  renderers = arrayOf(InventoryMarkdownReportRenderer())
}

tasks {
  val cleanLicenses by registering(Delete::class) {
    delete("licenses")
  }

  val copyLicenses by registering(Copy::class) {
    dependsOn(cleanLicenses)

    from("build/reports/dependency-license")
    into("licenses")
  }

  val generateLicenseReport by existing {
    finalizedBy(copyLicenses)
  }
}

nebulaRelease {
  addReleaseBranchPattern("main")
}
