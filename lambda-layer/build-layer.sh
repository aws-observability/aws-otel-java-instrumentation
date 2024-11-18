#!/bin/bash

SOURCEDIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"


## revert https://github.com/open-telemetry/opentelemetry-java-instrumentation/pull/7970
OTEL_VERSION="1.32.1"
ADOT_VERSION="1.32.1"


## Clone and Patch the OpenTelemetry Java Instrumentation Repository
git clone https://github.com/open-telemetry/opentelemetry-java-instrumentation.git
pushd opentelemetry-java-instrumentation
git checkout v${OTEL_VERSION} -b tag-v${OTEL_VERSION}
patch -p1 < "$SOURCEDIR"/patches/opentelemetry-java-instrumentation.patch
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

# Build ADOT Lambda Java SDK Layer Code
./gradlew build

## Move the ADOT Lambda Java SDK code into OTel Lambda Java folder - TODO: probably not needed since this is related to wrapper
#mkdir -p ../opentelemetry-lambda/java/layer-wrapper/build/extensions
#cp ./build/libs/aws-otel-lambda-java-extensions.jar ../opentelemetry-lambda/java/layer-wrapper/build/extensions

## Combine Java Agent build and ADOT Collector
#pushd ./layer-javaagent/build/distributions || exit
#unzip -qo opentelemetry-javaagent-layer.zip
#rm opentelemetry-javaagent-layer.zip
#mv otel-handler otel-handler-upstream
#cp "$SOURCEDIR"/scripts/otel-handler .

# Copy ADOT Java Agent downloaded using Gradle task
cp "$SOURCEDIR"/build/javaagent/aws-opentelemetry-agent*.jar ./opentelemetry-javaagent.jar
#unzip -qo ../../../../collector/build/opentelemetry-collector-layer-$1.zip - TODO: bundling collector not needed
zip -qr opentelemetry-javaagent-layer.zip opentelemetry-javaagent.jar otel-handler
#popd || exit
