#!/bin/bash
set -e

SOURCEDIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
file="$SOURCEDIR/../.github/patches/versions"

contrib_version=$(awk -F'=v' '/OTEL_JAVA_CONTRIB_VERSION/ {print $2}' "$file")
if [[ -n "$contrib_version" ]]; then
  echo "Found OTEL Contrib Version: ${contrib_version}"
  ## Clone and Patch the OpenTelemetry Java contrib Repository
  echo "Info: Cloning and Patching OpenTelemetry Java contrib Repository"
  git clone https://github.com/open-telemetry/opentelemetry-java-contrib.git
  pushd opentelemetry-java-contrib
  git checkout v${contrib_version} -b tag-v${contrib_version}

  # There is another patch in the .github/patches directory for other changes. We should apply them too for consistency.
  patch -p1 < "$SOURCEDIR"/../.github/patches/opentelemetry-java-contrib.patch

  ./gradlew publishToMavenLocal
  popd
  rm -rf opentelemetry-java-contrib
fi

## Get OTel version
echo "Info: Getting OTEL Version"
version=$(awk -F'=v' '/OTEL_JAVA_INSTRUMENTATION_VERSION/ {print $2}' "$file")
echo "Found OTEL Version: ${version}"
# Exit if the version is empty or null
if [[ -z "$version" ]]; then
  echo "Error: Version could not be found in ${file}."
  exit 1
fi


## Clone and Patch the OpenTelemetry Java Instrumentation Repository
echo "Info: Cloning and Patching OpenTelemetry Java Instrumentation Repository"
git clone https://github.com/open-telemetry/opentelemetry-java-instrumentation.git
pushd opentelemetry-java-instrumentation
git checkout v${version} -b tag-v${version}

# There is another patch in the .github/patches directory for other changes. We should apply them too for consistency.
patch -p1 < "$SOURCEDIR"/../.github/patches/opentelemetry-java-instrumentation.patch

# This patch is for Lambda related context propagation
patch -p1 < "$SOURCEDIR"/patches/opentelemetry-java-instrumentation.patch

./gradlew publishToMavenLocal
popd
rm -rf opentelemetry-java-instrumentation

## Build the ADOT Java from current source
echo "Info: Building ADOT Java from current source"
pushd "$SOURCEDIR"/..
patch  -p1 < "${SOURCEDIR}"/patches/aws-otel-java-instrumentation.patch
CI=false ./gradlew publishToMavenLocal -Prelease.version=${version}-adot-lambda1
popd


## Build ADOT Lambda Java SDK Layer Code
echo "Info: Building ADOT Lambda Java SDK Layer Code"
./gradlew build -PotelVersion=${otel_instrumentation_version} -Pversion=${version}


## Copy ADOT Java Agent downloaded using Gradle task and bundle it with the Lambda handler script
echo "Info: Creating the layer artifact"
mkdir -p "$SOURCEDIR"/build/distributions/
cp "$SOURCEDIR"/build/javaagent/aws-opentelemetry-agent*.jar "$SOURCEDIR"/build/distributions/aws-opentelemetry-javaagent.jar
cp otel-instrument "$SOURCEDIR"/build/distributions/otel-instrument
pushd "$SOURCEDIR"/build/distributions
zip -r aws-opentelemetry-java-layer.zip aws-opentelemetry-javaagent.jar otel-instrument
popd

## Cleanup
# revert the patch applied since it is only needed while building the layer.
echo "Info: Cleanup"
git restore ../dependencyManagement/build.gradle.kts
