#!/bin/bash

SOURCEDIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"


## revert https://github.com/open-telemetry/opentelemetry-java-instrumentation/pull/7970
OTEL_VERSION="1.32.1"
ADOT_VERSION="1.32.1"


## Clone and Patch the OpenTelemetry Java Instrumentation Repository
git clone https://github.com/open-telemetry/opentelemetry-java-instrumentation.git
pushd opentelemetry-java-instrumentation
git checkout v${OTEL_VERSION} -b tag-v${OTEL_VERSION}

# This patch is for Lambda related context propagation
patch -p1 < "$SOURCEDIR"/patches/opentelemetry-java-instrumentation.patch

# There is another patch in the .github/patches directory for other changes. We should apply them too for consistency.
patch -p1 < "$SOURCEDIR"/../.github/patches/opentelemetry-java-instrumentation.patch

git add -A
git commit -m "Create patch version"
./gradlew publishToMavenLocal
popd
rm -rf opentelemetry-java-instrumentation


## Build the ADOT Java from current source
pushd "$SOURCEDIR"/..
patch  -p1 < "${SOURCEDIR}"/patches/aws-otel-java-instrumentation.patch
CI=false ./gradlew publishToMavenLocal -Prelease.version=${ADOT_VERSION}-adot-lambda1
popd


## Build ADOT Lambda Java SDK Layer Code
./gradlew build


## Copy ADOT Java Agent downloaded using Gradle task and bundle it with the Lambda handler script
cp "$SOURCEDIR"/build/javaagent/aws-opentelemetry-agent*.jar ./opentelemetry-javaagent.jar
zip -qr opentelemetry-javaagent-layer.zip opentelemetry-javaagent.jar otel-handler
