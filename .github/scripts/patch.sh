#!/bin/bash -x

exit 1 # The following values must be change before every new patch release
OTEL_JAVA_VERSION=1.21.0
OTEL_JAVA_INSTRUMENTATION_VERSION=1.21.0
OTEL_CONTRIB_VERSION=1.21.0
PATCH_VERSION=1.21.1-adot

git config user.name github-actions
git config user.email github-actions@github.com

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

OTEL_JAVA_INSTRUMENTATION_PATCH=".github/patchs/opentelemetry-java-instrumentation.patch"
if [[ -f "$OTEL_JAVA_INSTRUMENTATION_PATCH" ]]; then
  git clone https://github.com/open-telemetry/opentelemetry-java-instrumentation.git
  cd opentelemetry-java-instrumentation
  git checkout v${OTEL_JAVA_INSTRUMENTATION_VERSION} -b tag-v${OTEL_JAVA_INSTRUMENTATION_VERSION}
  patch -p1 < "../${OTEL_JAVA_INSTRUMENTATION_PATCH}"
  git commit -a -m "Patching to release ${PATCH_VERSION}"
else
  echo "Skipping patching opentelemetry-java-instrumentation"
fi
