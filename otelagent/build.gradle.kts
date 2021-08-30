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
  java
  `maven-publish`

  id("com.google.cloud.tools.jib")
  id("com.github.johnrengelman.shadow")
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

base {
  archivesBaseName = "aws-opentelemetry-agent"
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
  val agentDep = create("io.opentelemetry.javaagent", "opentelemetry-javaagent", classifier = "all")
  shadowClasspath(agentDep)
  compileOnly(agentDep)
}

val bundledProjects = listOf(
  project(":awsagentprovider"),
  project(":instrumentation:log4j-2.13.2"),
  project(":instrumentation:logback-1.0")
)

for (bundled in bundledProjects) {
  evaluationDependsOn(bundled.path)
}

tasks {
  processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    for (bundled in bundledProjects) {
      val task = bundled.tasks.named<Jar>("shadowJar").get()
      val providerArchive = task.archiveFile
      from(zipTree(providerArchive)) {
        into("inst")
        rename("(^.*)\\.class$", "$1.classdata")
      }
      dependsOn(task)
    }
  }

  shadowJar {
    archiveClassifier.set("")

    configurations = listOf(shadowClasspath)

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
}

val shadowJar = tasks.named("shadowJar")
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
        }
      }
    }
  }
}

jib {
  to {
    image = "public.ecr.aws/u0d6r4y4/aws-opentelemetry-java-base:alpha"
  }
  from {
    image = "public.ecr.aws/u0d6r4y4/amazoncorretto-distroless:alpha"
  }
  container {
    appRoot = "/aws-observability"
    setEntrypoint("INHERIT")
    environment = mapOf("JAVA_TOOL_OPTIONS" to "-javaagent:/aws-observability/classpath/aws-opentelemetry-agent-$version.jar")
  }
  containerizingMode = "packaged"
}
