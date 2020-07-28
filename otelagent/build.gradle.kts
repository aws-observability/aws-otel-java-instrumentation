/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
    id("com.github.johnrengelman.shadow") version "5.2.0"
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_7
    targetCompatibility = JavaVersion.VERSION_1_7
}

dependencies {
    implementation("io.opentelemetry.instrumentation.auto", "opentelemetry-javaagent", classifier = "all")
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
            attributes.put("Main-Class", "io.opentelemetry.auto.bootstrap.AgentBootstrap")
            attributes.put("Agent-Class", "com.softwareaws.xray.opentelemetry.agentbootstrap.AwsAgentBootstrap")
            attributes.put("Premain-Class", "com.softwareaws.xray.opentelemetry.agentbootstrap.AwsAgentBootstrap")
            attributes.put("Can-Redefine-Classes", "true")
            attributes.put("Can-Retransform-Classes", "true")
        }
    }
}
