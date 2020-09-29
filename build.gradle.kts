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
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
  java
  id("nebula.release") version "15.1.0"
  id("com.diffplug.spotless") version "5.1.2"
  id("com.github.jk1.dependency-license-report") version "1.14"
}

val releaseTask = tasks.named("release")
val postReleaseTask = tasks.named("release")

allprojects {
  repositories {
    jcenter()
    mavenCentral()
    mavenLocal()

    maven {
      setUrl("https://oss.jfrog.org/libs-snapshot")
    }
  }

  project.group = "software.amazon.awsobservability"

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
      ktlint("0.38.0").userData(mapOf("indent_size" to "2", "continuation_indent_size" to "2"))

      licenseHeaderFile("${rootProject.projectDir}/config/license/header.java", "plugins|include|import")
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

        licenseHeaderFile("${rootProject.projectDir}/config/license/header.java")
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
            description.set(
              "The Amazon Web Services distribution of the OpenTelemetry Java Instrumentation."
            )

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
          }
        }
      }

      repositories {
        // For now, we only publish to GitHub Packages
        maven {
          name = "GitHubPackages"
          url = uri("https://maven.pkg.github.com/anuraaga/aws-opentelemetry-java-instrumentation")
          credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("PUBLISH_USERNAME")
            password = project.findProperty("gpr.key") as String? ?: System.getenv("PUBLISH_PASSWORD")
          }
        }
      }
    }
  }
}

tasks.named<Wrapper>("wrapper") {
  gradleVersion = "6.6.1"
  distributionType = Wrapper.DistributionType.ALL
  distributionSha256Sum = "11657af6356b7587bfb37287b5992e94a9686d5c8a0a1b60b87b9928a2decde5"
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
