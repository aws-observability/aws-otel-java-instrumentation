#!/bin/bash -x

exit 1 # Remove this lock after updating the following values

OTEL_JAVA_VERSION=1.21.0
OTEL_JAVA_INSTRUMENTATION_VERSION=1.21.0
OTEL_CONTRIB_VERSION=1.21.0
PATCH_VERSION=1.21.1-adot

git config --global user.email "github-actions@github.com"
git config --global user.name "github-actions"


OTEL_JAVA_PATCH=".github/patchs/opentelemetry-java.patch"
if [[ -f "$OTEL_JAVA_PATCH" ]]; then
  git clone https://github.com/open-telemetry/opentelemetry-java.git
  cd opentelemetry-java
  git checkout v${OTEL_JAVA_VERSION} -b tag-v${OTEL_JAVA_VERSION}
  patch -p1 < ../${OTEL_JAVA_PATCH}
  git commit -a -m "Patching to release ${PATCH_VERSION}"
  cd -
else
  echo "Skiping patching opentelemetry-java"
fi


OTEL_JAVA_CONTRIB_PATCH=".github/patchs/opentelemetry-java-contrib.patch"
if [[ -f "$OTEL_JAVA_CONTRIB_PATCH" ]]; then
  git clone https://github.com/open-telemetry/opentelemetry-java-contrib.git
  cd opentelemetry-java-contrib
  git checkout v${OTEL_JAVA_CONTRIB_VERSION} -b tag-v${OTEL_JAVA_CONTRIB_VERSION}
  patch -p1 < "../${OTEL_JAVA_CONTRIB_PATCH}"
  git commit -a -m "Patching to release ${PATCH_VERSION}"
  cd -
else
  echo "Skipping patching opentelemetry-java-contrib"
fi


OTEL_JAVA_INSTRUMENTATION_PATCH=".github/patchs/opentelemetry-java-instrumentation.patch"
if [[ -f "$OTEL_JAVA_INSTRUMENTATION_PATCH" ]]; then
  git clone https://github.com/open-telemetry/opentelemetry-java-instrumentation.git
  cd opentelemetry-java-instrumentation
  git checkout v${OTEL_JAVA_INSTRUMENTATION_VERSION} -b tag-v${OTEL_JAVA_INSTRUMENTATION_VERSION}
  patch -p1 < "../${OTEL_JAVA_INSTRUMENTATION_PATCH}"
  git commit -a -m "Patching to release ${PATCH_VERSION}"
  cd -
else
  echo "Skipping patching opentelemetry-java-instrumentation"
fi
