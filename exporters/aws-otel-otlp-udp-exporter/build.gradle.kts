plugins {
    id("java")
    id("java-library")
    id("maven-publish")
}

group = "software.opentelemetry.exporters.otlp.udp"
version = "1.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation(platform("io.opentelemetry:opentelemetry-bom:1.44.1"))
    implementation("io.opentelemetry:opentelemetry-api")
    implementation("io.opentelemetry:opentelemetry-sdk")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp-common")
    implementation("io.opentelemetry.proto:opentelemetry-proto:1.0.0-alpha")
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    implementation("org.apache.logging.log4j:log4j-api:2.24.1")
    implementation("org.apache.logging.log4j:log4j-core:2.24.1")
    implementation("org.slf4j:slf4j-simple:2.0.16")
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
        resources {
            srcDirs("src/main/resources")
        }
    }
    test {
        java {
            srcDirs("src/test/java")
        }
        resources {
            srcDirs("src/test/resources")
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
            "Implementation-Version" to project.version
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

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = "aws-otel-otlp-udp-exporter"
            version = project.version.toString()
        }
    }
}
