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

dependencies {
  implementation("io.opentelemetry.javaagent", "opentelemetry-javaagent", classifier = "all")
}

val agentProviderShadowJarTask = project(":awsagentprovider").tasks.named<Jar>("shadowJar")
tasks {
  processResources {
    val providerArchive = agentProviderShadowJarTask.get().archiveFile
    from(zipTree(providerArchive)) {
      into("inst")
      rename("(^.*)\\.class$", "$1.classdata")
    }
    dependsOn(agentProviderShadowJarTask)
  }

  shadowJar {
    archiveClassifier.set("")

    exclude("**/module-info.class")

    manifest {
      attributes.put("Main-Class", "io.opentelemetry.javaagent.OpenTelemetryAgent")
      attributes.put("Agent-Class", "software.amazon.opentelemetry.javaagent.bootstrap.AwsAgentBootstrap")
      attributes.put("Premain-Class", "software.amazon.opentelemetry.javaagent.bootstrap.AwsAgentBootstrap")
      attributes.put("Can-Redefine-Classes", "true")
      attributes.put("Can-Retransform-Classes", "true")
      attributes.put("Implementation-Version", project.version)
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
    image = "ghcr.io/anuraaga/aws-opentelemetry-java-base:alpha"
  }
  from {
    image = "ghcr.io/anuraaga/amazoncorretto-distroless:alpha"
  }
  container {
    appRoot = "/aws-observability"
    setEntrypoint("INHERIT")
    environment = mapOf("JAVA_TOOL_OPTIONS" to "-javaagent:/aws-observability/classpath/aws-opentelemetry-agent-$version.jar")
  }
  containerizingMode = "packaged"
}
