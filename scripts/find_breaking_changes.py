#!/usr/bin/env python3
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

import os
import re
import sys

import requests
from packaging import version


def get_current_version_from_gradle():
    """Extract current OpenTelemetry versions from build.gradle.kts."""
    try:
        with open("dependencyManagement/build.gradle.kts", "r", encoding="utf-8") as file:
            content = file.read()

        # Extract otelVersion (instrumentation version) and strip -adot1 suffix
        otel_version_match = re.search(r'val otelVersion = "([^"]*)"', content)
        current_instrumentation_version = otel_version_match.group(1) if otel_version_match else None
        if current_instrumentation_version and current_instrumentation_version.endswith("-adot1"):
            current_instrumentation_version = current_instrumentation_version[:-6]  # Remove -adot1

        # Extract contrib version from dependency line and strip -adot1 suffix
        contrib_match = re.search(r'"io\.opentelemetry\.contrib:opentelemetry-aws-xray:([^"]*)",', content)
        current_contrib_version = contrib_match.group(1) if contrib_match else None
        if current_contrib_version and current_contrib_version.endswith("-adot1"):
            current_contrib_version = current_contrib_version[:-6]  # Remove -adot1

        return current_instrumentation_version, current_contrib_version

    except (OSError, IOError) as error:
        print(f"Error reading current versions: {error}")
        return None, None


def get_releases_with_breaking_changes(repo, current_version, new_version):
    """Get releases between current and new version that mention breaking changes."""
    try:
        response = requests.get(f"https://api.github.com/repos/open-telemetry/{repo}/releases", timeout=30)
        response.raise_for_status()
        releases = response.json()

        breaking_releases = []

        for release in releases:
            try:
                tag_name = release["tag_name"]
                release_version = tag_name.lstrip("v")

                # Check if this release is between current and new version
                if (
                    version.parse(current_version)
                    < version.parse(release_version)
                    <= version.parse(new_version)
                ):

                    # Check if release notes have breaking changes header or bold text
                    body = release.get("body", "")
                    if re.search(r"^(#+|\*\*)\s*breaking changes", body, re.MULTILINE | re.IGNORECASE):
                        breaking_releases.append(
                            {
                                "version": release_version,
                                "name": release["name"],
                                "url": release["html_url"],
                                "body": release.get("body", ""),
                            }
                        )
            except (ValueError, KeyError) as parse_error:
                print(f"Warning: Skipping release {release.get('name', 'unknown')} due to error: {parse_error}")
                continue

        return breaking_releases

    except requests.RequestException as request_error:
        print(f"Warning: Could not get releases for {repo}: {request_error}")
        return []


def main():
    new_instrumentation_version = os.environ.get("OTEL_JAVA_INSTRUMENTATION_VERSION")
    new_contrib_version = os.environ.get("OTEL_JAVA_CONTRIB_VERSION")

    if not new_instrumentation_version or not new_contrib_version:
        print("Error: OTEL_JAVA_INSTRUMENTATION_VERSION and OTEL_JAVA_CONTRIB_VERSION environment variables required")
        sys.exit(1)

    current_instrumentation_version, current_contrib_version = get_current_version_from_gradle()

    if not current_instrumentation_version:
        print("Could not determine current versions")
        sys.exit(1)

    print("Checking for breaking changes:")
    print(f"Instrumentation: {current_instrumentation_version} → {new_instrumentation_version}")
    print(f"Contrib: {current_contrib_version or 'unknown'} → {new_contrib_version}")

    # Check both repos for breaking changes
    instrumentation_breaking = get_releases_with_breaking_changes(
        "opentelemetry-java-instrumentation", current_instrumentation_version, new_instrumentation_version
    )
    contrib_breaking = []
    if current_contrib_version:
        contrib_breaking = get_releases_with_breaking_changes(
            "opentelemetry-java-contrib", current_contrib_version, new_contrib_version
        )

    # Output for GitHub Actions
    breaking_info = ""

    if instrumentation_breaking:
        breaking_info += "**opentelemetry-java-instrumentation:**\n"
        for release in instrumentation_breaking:
            breaking_info += f"- [{release['name']}]({release['url']})\n"

    if contrib_breaking:
        breaking_info += "\n**opentelemetry-java-contrib:**\n"
        for release in contrib_breaking:
            breaking_info += f"- [{release['name']}]({release['url']})\n"

    # Set GitHub output
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output_file:
            output_file.write(f"breaking_changes_info<<EOF\n{breaking_info}EOF\n")


if __name__ == "__main__":
    main()
