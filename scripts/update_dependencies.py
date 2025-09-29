#!/usr/bin/env python3

import requests
import re
import sys

def get_latest_instrumentation_version():
    """Get the latest version of opentelemetry-java-instrumentation from GitHub releases."""
    try:
        response = requests.get(
            'https://api.github.com/repos/open-telemetry/opentelemetry-java-instrumentation/releases/latest',
            timeout=30
        )
        response.raise_for_status()
        
        release_data = response.json()
        tag_name = release_data['tag_name']
        
        version = tag_name.lstrip('v')
        return version
        
    except requests.RequestException as request_error:
        print(f"Warning: Could not get latest instrumentation version: {request_error}")
        return None

def get_latest_contrib_version():
    """Get the latest version of opentelemetry-java-contrib from GitHub releases."""
    try:
        response = requests.get(
            'https://api.github.com/repos/open-telemetry/opentelemetry-java-contrib/releases',
            timeout=30
        )
        response.raise_for_status()
        
        releases = response.json()
        
        # Find the latest stable release
        for release in releases:
            if release.get('prerelease', False):
                continue
            
            tag_name = release['tag_name']
            version_match = re.match(r'^v?(\d+\.\d+\.\d+)$', tag_name)
            if version_match:
                version = version_match.group(1)
                print(f"Found contrib version: {version}")
                return version
        
        print("Warning: No stable contrib releases found")
        return None
        
    except requests.RequestException as request_error:
        print(f"Warning: Could not get latest contrib version: {request_error}")
        return None

def get_latest_maven_version(group_id, artifact_id):
    """Get the latest version of a Maven artifact from Maven Central."""
    try:
        response = requests.get(
            f'https://search.maven.org/solrsearch/select?q=g:{group_id}+AND+a:{artifact_id}&rows=1&wt=json',
            timeout=30
        )
        response.raise_for_status()
        
        data = response.json()
        docs = data.get('response', {}).get('docs', [])
        
        if docs:
            return docs[0]['latestVersion']
        else:
            print(f"Warning: No versions found for {group_id}:{artifact_id}")
            return None
            
    except requests.RequestException as request_error:
        print(f"Warning: Could not get latest version for {group_id}:{artifact_id}: {request_error}")
        return None

def update_gradle_file(file_path):
    """Update OpenTelemetry versions in build.gradle.kts."""
    try:
        with open(file_path, 'r', encoding='utf-8') as input_file:
            content = input_file.read()
        
        original_content = content
        updated = False
        
        latest_instrumentation_version = get_latest_instrumentation_version()
        if latest_instrumentation_version:
            # Update otelVersion
            otel_version_pattern = r'val otelVersion = "[^"]*"'
            otel_version_replacement = f'val otelVersion = "{latest_instrumentation_version}"'
            if re.search(otel_version_pattern, content):
                new_content = re.sub(otel_version_pattern, otel_version_replacement, content)
                if new_content != content:
                    content = new_content
                    updated = True
                    print(f"Updated otelVersion to {latest_instrumentation_version}")
            
            # Update otelSnapshotVersion (typically next minor version)
            version_parts = latest_instrumentation_version.split('.')
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
        
        # Get latest contrib version from GitHub
        latest_contrib_version = get_latest_contrib_version()
        
        contrib_packages = [
            ('io.opentelemetry.contrib', 'opentelemetry-aws-xray'),
            ('io.opentelemetry.contrib', 'opentelemetry-aws-resources'),
        ]
        
        for group_id, artifact_id in contrib_packages:
            if latest_contrib_version:
                # Pattern to match the dependency line
                pattern = rf'"{re.escape(group_id)}:{re.escape(artifact_id)}:[^"]*"'
                replacement = f'"{group_id}:{artifact_id}:{latest_contrib_version}"'
                
                if re.search(pattern, content):
                    new_content = re.sub(pattern, replacement, content)
                    if new_content != content:
                        content = new_content
                        updated = True
                        print(f"Updated {group_id}:{artifact_id} to {latest_contrib_version}")
        
        # Update remaining packages using Maven Central
        other_packages = [
            ('io.opentelemetry', 'opentelemetry-extension-aws'),
            ('io.opentelemetry.proto', 'opentelemetry-proto'),
        ]
        
        for group_id, artifact_id in other_packages:
            latest_version = get_latest_maven_version(group_id, artifact_id)
            if latest_version:
                # Pattern to match the dependency line
                pattern = rf'"{re.escape(group_id)}:{re.escape(artifact_id)}:[^"]*"'
                replacement = f'"{group_id}:{artifact_id}:{latest_version}"'
                
                if re.search(pattern, content):
                    new_content = re.sub(pattern, replacement, content)
                    if new_content != content:
                        content = new_content
                        updated = True
                        print(f"Updated {group_id}:{artifact_id} to {latest_version}")
        
        if updated:
            with open(file_path, 'w', encoding='utf-8') as output_file:
                output_file.write(content)
            print("Dependencies updated successfully")
            return True
        else:
            print("No OpenTelemetry dependencies needed updating")
            return False
            
    except (OSError, IOError) as file_error:
        print(f"Error updating dependencies: {file_error}")
        sys.exit(1)

def main():
    gradle_file_path = 'dependencyManagement/build.gradle.kts'
    
    updated = update_gradle_file(gradle_file_path)
    
    if not updated:
        print("No updates were made")

if __name__ == '__main__':
    main()
