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

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import software.amazon.adot.configureImages
plugins {
  java
  `maven-publish`

  id("com.google.cloud.tools.jib")
  id("com.gradleup.shadow")
  id("org.owasp.dependencycheck")
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

base {
  archivesName.set("aws-opentelemetry-agent")
}

val javaagentLibs by configurations.creating {
  isCanBeResolved = true
  isCanBeConsumed = false

  exclude("io.opentelemetry", "opentelemetry-api")
  exclude("io.opentelemetry", "opentelemetry-sdk")
  exclude("io.opentelemetry", "opentelemetry-sdk-common")
  exclude("io.opentelemetry.semconv", "opentelemetry-semconv")
  // Once io.opentelemetry.contrib:opentelemetry-aws-resources starts using
  // the new semantic convention packages we will need to exclude it here.
  // io.opentelemetry.semconv:opentelemetry-semconv
}

val shadowClasspath by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
  attributes {
    attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
  }
}

dependencies {
  // Ensure dependency doesn't leak into POMs by using compileOnly and shadow-specific configuration.
  val agentDep = create("io.opentelemetry.javaagent", "opentelemetry-javaagent")
  shadowClasspath(agentDep)
  compileOnly(agentDep)

  javaagentLibs(project(":awsagentprovider"))
  javaagentLibs(project(":instrumentation:log4j-2.13.2"))
  javaagentLibs(project(":instrumentation:logback-1.0"))
  javaagentLibs(project(":instrumentation:jmx-metrics"))
}

tasks {
  val relocateJavaagentLibs by registering(ShadowJar::class) {
    configurations = listOf(javaagentLibs)

    duplicatesStrategy = DuplicatesStrategy.FAIL

    archiveFileName.set("javaagentLibs-relocated.jar")
  }

  val shadowJar by existing(ShadowJar::class) {
    dependsOn(relocateJavaagentLibs)

    archiveClassifier.set("")

    configurations = listOf(shadowClasspath)

    isolateClasses(relocateJavaagentLibs.get().outputs.files)

    exclude("**/module-info.class")

    mergeServiceFiles("inst/META-INF/services")

    manifest {
      attributes.put("Main-Class", "io.opentelemetry.javaagent.OpenTelemetryAgent")
      attributes.put("Agent-Class", "software.amazon.opentelemetry.javaagent.bootstrap.AwsAgentBootstrap")
      attributes.put("Premain-Class", "software.amazon.opentelemetry.javaagent.bootstrap.AwsAgentBootstrap")
      attributes.put("Can-Redefine-Classes", "true")
      attributes.put("Can-Retransform-Classes", "true")

      val versionString = project.version.toString()
      val implementationVersion: String
      if (versionString.endsWith("-SNAPSHOT")) {
        implementationVersion = "${versionString.dropLast("-SNAPSHOT".length)}-aws-SNAPSHOT"
      } else {
        implementationVersion = "$versionString-aws"
      }
      attributes.put("Implementation-Version", implementationVersion)
    }
  }

  // Since we are reconfiguring the publishing procedure to only include the shadow jar, the Gradle metadata file
  // generated is no longer valid. Instead we should rely only on the pom generated.
  withType<GenerateModuleMetadata>().configureEach {
    enabled = false
  }
}

val shadowJar by tasks.existing(ShadowJar::class)
tasks {
  named("jar") {
    enabled = false
    dependsOn("shadowJar")
  }

  named<Jar>("shadowJar") {
    publishing {
      publications {
        named<MavenPublication>("maven") {
          artifact(archiveFile)
          pom {
            // Force packaging in the POM.
            packaging = "jar"
          }
        }
      }
    }
  }
}

jib {
  configureImages(
    "gcr.io/distroless/java17-debian11:debug",
    "public.ecr.aws/aws-otel-test/aws-opentelemetry-java-base:alpha-v2",
    localDocker = false,
    multiPlatform = !rootProject.property("localDocker")!!.equals("true"),
  )

  container {
    appRoot = "/aws-observability"
    setEntrypoint("INHERIT")
    environment = mapOf(
      "JAVA_TOOL_OPTIONS" to "-javaagent:/aws-observability/classpath/aws-opentelemetry-agent-$version.jar",
    )
  }
  containerizingMode = "packaged"
}

fun CopySpec.isolateClasses(jars: Iterable<File>) {
  jars.forEach {
    from(zipTree(it)) {
      // important to keep prefix "inst" short, as it is prefixed to lots of strings in runtime mem
      into("inst")
      rename("(^.*)\\.class\$", "\$1.classdata")
      // Rename LICENSE file since it clashes with license dir on non-case sensitive FSs (i.e. Mac)
      rename("""^LICENSE$""", "LICENSE.renamed")
      exclude("META-INF/INDEX.LIST")
      exclude("META-INF/*.DSA")
      exclude("META-INF/*.SF")
    }
  }
}
