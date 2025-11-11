#!/usr/bin/env python3
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

import os
import re
import sys

import requests


def get_latest_otel_versions():
    """Get latest OpenTelemetry versions from GitHub releases."""
    try:
        # Get latest instrumentation version
        response = requests.get(
            "https://api.github.com/repos/open-telemetry/opentelemetry-java-instrumentation/releases/latest", timeout=30
        )
        response.raise_for_status()

        release_data = response.json()
        otel_java_instrumentation_version = release_data["tag_name"].lstrip("v")

        # Get latest contrib version
        contrib_response = requests.get(
            "https://api.github.com/repos/open-telemetry/opentelemetry-java-contrib/releases", timeout=30
        )
        contrib_response.raise_for_status()

        contrib_releases = contrib_response.json()
        otel_java_contrib_version = None

        # Find the latest stable release
        for release in contrib_releases:
            if release.get("prerelease", False):
                continue

            tag_name = release["tag_name"]
            version_match = re.match(r"^v?(\d+\.\d+\.\d+)$", tag_name)
            if version_match:
                otel_java_contrib_version = version_match.group(1)
                break

        if not otel_java_contrib_version:
            print("Warning: No stable contrib releases found")
            otel_java_contrib_version = "1.48.0"  # fallback

        return otel_java_instrumentation_version, otel_java_contrib_version

    except requests.RequestException as request_error:
        print(f"Error getting OpenTelemetry versions: {request_error}")
        sys.exit(1)


def main():
    otel_java_instrumentation_version, otel_java_contrib_version = get_latest_otel_versions()

    print(f"OTEL_JAVA_INSTRUMENTATION_VERSION={otel_java_instrumentation_version}")
    print(f"OTEL_JAVA_CONTRIB_VERSION={otel_java_contrib_version}")

    # Write to GitHub output if in CI
    if "GITHUB_OUTPUT" in os.environ:
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output_file:
            output_file.write(f"otel_java_instrumentation_version={otel_java_instrumentation_version}\n")
            output_file.write(f"otel_java_contrib_version={otel_java_contrib_version}\n")


if __name__ == "__main__":
    main()