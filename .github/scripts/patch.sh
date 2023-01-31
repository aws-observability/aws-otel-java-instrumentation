#!/bin/bash -x -e -o nounset

# .github/patchs/.versions.sh should define all the versions of the dependencies that we are going to patch
# This is used so that we can properly clone the upstream repositories.
# This file should define the following variables:
# OTEL_JAVA_VERSION. Tag of the opentelemetry-java repository to use. E.g.: JAVA_OTEL_JAVA_VERSION=v1.21.0
# OTEL_JAVA_INSTRUMENTATION_VERSION. Tag of the opentelemetry-java-instrumentation repository to use, e.g.: OTEL_JAVA_INSTRUMENTATION_VERSION=v1.21.0
# OTEL_JAVA_CONTRIB_VERSION. Tag of the opentelemetry-java-contrib repository. E.g.: OTEL_JAVA_CONTRIB_VERSION=v1.21.0
# This script will fail if a variable that is supposed to exist is referenced.

if [[ ! -f .github/patchs/versions ]]; then
  echo "No versions file found. Skipping patching"
  exit 0
fi

source .github/patchs/versions

git config --global user.email "github-actions@github.com"
git config --global user.name "github-actions"


OTEL_JAVA_PATCH=".github/patchs/opentelemetry-java.patch"
if [[ -f "$OTEL_JAVA_PATCH" ]]; then
  git clone https://github.com/open-telemetry/opentelemetry-java.git
  cd opentelemetry-java
  git checkout ${OTEL_JAVA_VERSION} -b tag-${OTEL_JAVA_VERSION}
  patch -p1 < ../${OTEL_JAVA_PATCH}
  git commit -a -m "ADOT Patch release"
  cd -
else
  echo "Skiping patching opentelemetry-java"
fi


OTEL_JAVA_CONTRIB_PATCH=".github/patchs/opentelemetry-java-contrib.patch"
if [[ -f "$OTEL_JAVA_CONTRIB_PATCH" ]]; then
  git clone https://github.com/open-telemetry/opentelemetry-java-contrib.git
  cd opentelemetry-java-contrib
  git checkout ${OTEL_JAVA_CONTRIB_VERSION} -b tag-${OTEL_JAVA_CONTRIB_VERSION}
  patch -p1 < "../${OTEL_JAVA_CONTRIB_PATCH}"
  git commit -a -m "ADOT Patch release"
  cd -
else
  echo "Skipping patching opentelemetry-java-contrib"
fi


OTEL_JAVA_INSTRUMENTATION_PATCH=".github/patchs/opentelemetry-java-instrumentation.patch"
if [[ -f "$OTEL_JAVA_INSTRUMENTATION_PATCH" ]]; then
  git clone https://github.com/open-telemetry/opentelemetry-java-instrumentation.git
  cd opentelemetry-java-instrumentation
  git checkout ${OTEL_JAVA_INSTRUMENTATION_VERSION} -b tag-${OTEL_JAVA_INSTRUMENTATION_VERSION}
  patch -p1 < "../${OTEL_JAVA_INSTRUMENTATION_PATCH}"
  git commit -a -m "ADOT Patch release"
  cd -
else
  echo "Skipping patching opentelemetry-java-instrumentation"
fi
