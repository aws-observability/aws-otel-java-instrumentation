#!/usr/bin/env python3

import re
import requests
import sys
from packaging import version


def get_current_versions():
    """Get current versions from build.gradle.kts."""
    try:
        with open("dependencyManagement/build.gradle.kts", "r", encoding="utf-8") as file:
            content = file.read()

        # Extract otelVersion
        otel_version_match = re.search(r'val otelVersion = "([^"]*)"', content)
        current_instrumentation_version = otel_version_match.group(1) if otel_version_match else None

        return current_instrumentation_version

    except (OSError, IOError) as error:
        print(f"Error reading current versions: {error}")
        return None


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

                    # Check if release notes have breaking changes as headers
                    body = release.get("body", "")
                    breaking_header_pattern = r'^\s*#+.*Breaking changes'
                    if re.search(breaking_header_pattern, body, re.MULTILINE):
                        breaking_releases.append(
                            {
                                "version": release_version,
                                "name": release["name"],
                                "url": release["html_url"],
                                "body": release.get("body", ""),
                            }
                        )
            except (ValueError, KeyError):
                continue

        return breaking_releases

    except requests.RequestException as request_error:
        print(f"Warning: Could not get releases for {repo}: {request_error}")
        return []


def main():
    current_instrumentation_version = get_current_versions()

    if not current_instrumentation_version:
        print("Could not determine current versions")
        sys.exit(1)

    # Get new versions from the update script
    sys.path.append('scripts')
    from update_dependencies import get_latest_instrumentation_version, get_latest_contrib_version

    new_instrumentation_version = get_latest_instrumentation_version()
    new_contrib_version = get_latest_contrib_version()

    if not new_instrumentation_version:
        print("Could not determine new versions")
        sys.exit(1)

    print("Checking for breaking changes:")
    print(f"Instrumentation: {current_instrumentation_version} → {new_instrumentation_version}")
    if new_contrib_version:
        print(f"Contrib: → {new_contrib_version}")

    # Check instrumentation repo for breaking changes
    instrumentation_breaking = get_releases_with_breaking_changes(
        "opentelemetry-java-instrumentation", current_instrumentation_version, new_instrumentation_version
    )

    # Output for GitHub Actions
    breaking_info = ""

    if instrumentation_breaking:
        breaking_info += "**opentelemetry-java-instrumentation:**\n"
        for release in instrumentation_breaking:
            breaking_info += f"- [{release['name']}]({release['url']})\n"

    if new_contrib_version:
        breaking_info += "\n**Check contrib releases for potential breaking changes:**\n"
        breaking_info += "- [opentelemetry-java-contrib releases](https://github.com/open-telemetry/opentelemetry-java-contrib/releases)\n"

    import os
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output_file:
            output_file.write(f"breaking_changes_info<<EOF\n{breaking_info}EOF\n")


if __name__ == "__main__":
    main()
