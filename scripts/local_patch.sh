#!/bin/bash
# Enable debug mode, fail on any command that fail in this script and fail on unset variables
set -x -e -u

if [[ ! -f .github/patches/versions ]]; then
  echo "No versions file found. Skipping patching"
  exit 0
fi

rm -rf ~/.m2/repository/
source .github/patches/versions


# Patching opentelemetry-java
OTEL_JAVA_PATCH=".github/patches/opentelemetry-java.patch"
if [[ -f "$OTEL_JAVA_PATCH" ]]; then
  echo "Patching opentelemetry-java"
  git clone https://github.com/open-telemetry/opentelemetry-java.git
  cd opentelemetry-java

  echo "Checking out tag ${OTEL_JAVA_VERSION}"
  git checkout ${OTEL_JAVA_VERSION} -b tag-${OTEL_JAVA_VERSION}
  patch -p1 < ../${OTEL_JAVA_PATCH}
  git commit -a -m "ADOT Patch release"

  echo "Building patched opentelemetry-java"
  ./gradlew clean assemble
  ./gradlew publishToMavenLocal
  cd -

  echo "Cleaning up opentelemetry-java"
  rm -rf opentelemetry-java
else
  echo "Skiping patching opentelemetry-java"
fi


# Patching opentelemetry-java-contrib
OTEL_JAVA_CONTRIB_PATCH=".github/patches/opentelemetry-java-contrib.patch"
if [[ -f "$OTEL_JAVA_CONTRIB_PATCH" ]]; then
  echo "Patching opentelemetry-java-contrib"
  git clone https://github.com/open-telemetry/opentelemetry-java-contrib.git
  cd opentelemetry-java-contrib

  echo "Checking out tag ${OTEL_JAVA_CONTRIB_VERSION}"
  git checkout ${OTEL_JAVA_CONTRIB_VERSION} -b tag-${OTEL_JAVA_CONTRIB_VERSION}
  patch -p1 < "../${OTEL_JAVA_CONTRIB_PATCH}"
  git commit -a -m "ADOT Patch release"

  echo "Building patched opentelemetry-java-contrib"
  ./gradlew clean assemble
  ./gradlew publishToMavenLocal
  cd -

  echo "Cleaning up opentelemetry-java-contrib"
  rm -rf opentelemetry-java-contrib
else
  echo "Skipping patching opentelemetry-java-contrib"
fi


# Patching opentelemetry-java-instrumentation
OTEL_JAVA_INSTRUMENTATION_PATCH=".github/patches/opentelemetry-java-instrumentation.patch"
if [[ -f "$OTEL_JAVA_INSTRUMENTATION_PATCH" ]]; then
  echo "Patching opentelemetry-java-instrumentation"
  git clone https://github.com/open-telemetry/opentelemetry-java-instrumentation.git
  cd opentelemetry-java-instrumentation

  echo "Checking out tag ${OTEL_JAVA_INSTRUMENTATION_VERSION}"
  git checkout ${OTEL_JAVA_INSTRUMENTATION_VERSION} -b tag-${OTEL_JAVA_INSTRUMENTATION_VERSION}
  patch -p1 < "../${OTEL_JAVA_INSTRUMENTATION_PATCH}"
  git commit -a -m "ADOT Patch release"

  echo "Building patched opentelemetry-java-instrumentation"
  ./gradlew clean assemble
  ./gradlew publishToMavenLocal
  cd -

  echo "Cleaning up opentelemetry-java-instrumentation"
  rm -rf opentelemetry-java-instrumentation
else
  echo "Skipping patching opentelemetry-java-instrumentation"
fi