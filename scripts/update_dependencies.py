#!/usr/bin/env python3
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

import os
import re
import sys

# Dependencies that use the instrumentation version
INSTRUMENTATION_DEPS = [
    "io.opentelemetry.javaagent:opentelemetry-javaagent",
]

# Dependencies that use the contrib version
CONTRIB_DEPS = [
    "io.opentelemetry.contrib:opentelemetry-aws-xray",
    "io.opentelemetry.contrib:opentelemetry-aws-resources",
]

# Other OpenTelemetry dependencies with independent versioning
OTHER_OTEL_DEPS = [
    "io.opentelemetry:opentelemetry-extension-aws",
    "io.opentelemetry.proto:opentelemetry-proto",
]

def update_file_dependencies(file_path, otel_instrumentation_version, otel_contrib_version):
    """Update all OpenTelemetry dependencies in a given file"""
    try:
        with open(file_path, "r", encoding="utf-8") as input_file:
            content = input_file.read()

        updated = False

        # Update otelVersion variable
        otel_version_pattern = r'val otelVersion = "[^"]*"'
        otel_version_replacement = f'val otelVersion = "{otel_instrumentation_version}"'
        if re.search(otel_version_pattern, content):
            new_content = re.sub(otel_version_pattern, otel_version_replacement, content)
            if new_content != content:
                content = new_content
                updated = True
                print(f"Updated otelVersion to {otel_instrumentation_version}")

        # Update otelSnapshotVersion (typically next minor version)
        version_parts = otel_instrumentation_version.split(".")
        if len(version_parts) >= 2:
            next_minor = f"{version_parts[0]}.{int(version_parts[1]) + 1}.0"
            otel_snapshot_pattern = r'val otelSnapshotVersion = "[^"]*"'
            otel_snapshot_replacement = f'val otelSnapshotVersion = "{next_minor}"'
            if re.search(otel_snapshot_pattern, content):
                new_content = re.sub(otel_snapshot_pattern, otel_snapshot_replacement, content)
                if new_content != content:
                    content = new_content
                    updated = True
                    print(f"Updated otelSnapshotVersion to {next_minor}")

        # Update contrib dependencies
        for dep in CONTRIB_DEPS:
            pattern = rf'"{re.escape(dep)}:[^"]*"'
            replacement = f'"{dep}:{otel_contrib_version}"'
            if re.search(pattern, content):
                new_content = re.sub(pattern, replacement, content)
                if new_content != content:
                    content = new_content
                    updated = True
                    print(f"Updated {dep} to {otel_contrib_version}")

        if updated:
            with open(file_path, "w", encoding="utf-8") as output_file:
                output_file.write(content)
            print(f"Updated {file_path}")

        return updated
    except (OSError, IOError) as file_error:
        print(f"Error updating {file_path}: {file_error}")
        return False

def main():
    otel_instrumentation_version = os.environ.get("OTEL_JAVA_INSTRUMENTATION_VERSION")
    otel_contrib_version = os.environ.get("OTEL_JAVA_CONTRIB_VERSION")

    if not otel_instrumentation_version or not otel_contrib_version:
        print("Error: OTEL_JAVA_INSTRUMENTATION_VERSION and OTEL_JAVA_CONTRIB_VERSION environment variables required")
        sys.exit(1)

    # Files to update
    files_to_update = [
        "dependencyManagement/build.gradle.kts",
    ]

    any_updated = False
    for file_path in files_to_update:
        if update_file_dependencies(file_path, otel_instrumentation_version, otel_contrib_version):
            any_updated = True

    if any_updated:
        print(f"Dependencies updated to Instrumentation {otel_instrumentation_version} / Contrib {otel_contrib_version}")
    else:
        print("No OpenTelemetry dependencies found to update")

if __name__ == "__main__":
    main()
