#!/bin/bash
# Builds the PATCHED upstream OpenTelemetry dependencies (contrib + instrumentation)
# These are the slow, rarely-changing steps of the lambda layer build. They only
# need to rerun when the pinned upstream versions (.github/patches/versions) or the
# patch files change. In CI, this script is skipped on an actions/cache hit keyed on
# exactly those files; build-layer.sh (ADOT from source + package) always runs after.
set -e

SOURCEDIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
file="$SOURCEDIR/../.github/patches/versions"

## Clone and Patch the OpenTelemetry Java contrib Repository
contrib_version=$(awk -F'=v' '/OTEL_JAVA_CONTRIB_VERSION/ {print $2}' "$file")
if [[ -n "$contrib_version" ]]; then
  echo "Found OTEL Contrib Version: ${contrib_version}"
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

if [ -f "$SOURCEDIR"/../.github/patches/opentelemetry-java-instrumentation.patch ]; then
  patch -p1 < "$SOURCEDIR"/../.github/patches/opentelemetry-java-instrumentation.patch
fi

# This patch is for Lambda related context propagation
patch -p1 < "$SOURCEDIR"/patches/opentelemetry-java-instrumentation.patch

./gradlew publishToMavenLocal
popd
rm -rf opentelemetry-java-instrumentation

echo "Info: Patched upstream dependencies published to local Maven repository"
