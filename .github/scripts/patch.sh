#!/bin/bash
# Enable debug mode, fail on any command that fail in this script and fail on unset variables
set -x -e -u

# This parameter will help find the patches to be applied
BRANCH=$1

# .github/patches/$BRANCH/versions.sh should define all the versions of the dependencies that we are going to patch
# This is used so that we can properly clone the upstream repositories.
# This file should define the following variables:
# OTEL_JAVA_VERSION. Tag of the opentelemetry-java repository to use. E.g.: JAVA_OTEL_JAVA_VERSION=v1.21.0
# OTEL_JAVA_INSTRUMENTATION_VERSION. Tag of the opentelemetry-java-instrumentation repository to use, e.g.: OTEL_JAVA_INSTRUMENTATION_VERSION=v1.21.0
# OTEL_JAVA_CONTRIB_VERSION. Tag of the opentelemetry-java-contrib repository. E.g.: OTEL_JAVA_CONTRIB_VERSION=v1.21.0
# This script will fail if a variable that is supposed to exist is referenced.

if [[ ! -f .github/patches/${BRANCH}/versions ]]; then
  echo "No versions file found. Skipping patching"
  exit 0
fi

source .github/patches/${BRANCH}/versions

git config --global user.email "adot-patch-workflow@github.com"
git config --global user.name "ADOT Patch workflow"


OTEL_JAVA_PATCH=".github/patches/${BRANCH}/opentelemetry-java.patch"
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


OTEL_JAVA_CONTRIB_PATCH=".github/patches/${BRANCH}/opentelemetry-java-contrib.patch"
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


OTEL_JAVA_INSTRUMENTATION_PATCH=".github/patches/${BRANCH}/opentelemetry-java-instrumentation.patch"
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
