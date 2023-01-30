# AWS OpenTelemetry Java Instrumentation Release Process

## Preparing for a release

Before beginning a release, make sure [dependencies](https://github.com/aws-observability/aws-otel-java-instrumentation/blob/main/dependencyManagement/build.gradle.kts) are updated.

Run `./gradlew dependencyUpdates` to get a report on what dependencies have updates and apply them to
`dependencyManagement` and `settings.gradle.kts`.

Then, run `rm -rf licenses && ./gradlew --no-build-cache --rerun-tasks generateLicenseReport` to update our licenses to reflect the update. Send a PR
and merge.

## Starting the Release

Open the release build workflow in your browser [here](https://github.com/aws-observability/aws-otel-java-instrumentation/actions?query=workflow%3A%22Release+Build%22).

You will see a button that says "Run workflow". Press the button, enter the version number you want
to release in the input field that pops up, and then press "Run workflow".

This triggers the release process, which builds the artifacts, updates the README with the new
version numbers, commits the change to the README, publishes the artifacts, creates and pushes
a git tag with the version number, and drafts a release with the agent artifact attached.

## Announcement
   
Once the GitHub workflow completes, go to Github [release
page](https://github.com/aws-observability/aws-otel-java-instrumentation/releases), and find the
generated draft release to write release notes about the new release.

You can use `git log upstream/v$MAJOR.$((MINOR-1)).x..upstream/v$MAJOR.$MINOR.x --graph --first-parent`
or the Github [compare tool](https://github.com/open-telemetry/opentelemetry-java/compare/)
to view a summary of all commits since last release as a reference.

## Patch Release

All patch releases should include only bug-fixes, and must avoid
adding/modifying the public APIs. 

Steps:
1. Create a branch from the release that you want to patch. It should follow the convention `release-<major>.<minor>.x`. E.g.: if you want to patch release 1.21.0, the name of the branch should be `release-1.21.x`.
1. Mark the branch as protected.
1. Modify the source code/dependencies. You can only update the patch version of opentelemetry dependencies.
1. Optionally prepare patches that can be applied to opentelemetry-java and opentelemetry-java-instrumentation. Use the sufix `-adot` in the
patched versions.
1. Create pull request to merge in the release branch.

After the pull request is merged, open the release build workflow in your browser [here](https://github.com/aws-observability/aws-otel-java-instrumentation/actions?query=workflow%3A%22Release+Build%22).

Select the branch and provide the version.

## Release candidates

Release candidate artifacts are released using the same process described above. The version schema for release candidates
is`v1.2.3-RC$`, where `$` denotes a release candidate version, e.g. `v1.2.3-RC1`.

## Credentials

The following credentials are required for publishing (and automatically set in CI):

* `PUBLISH_USERNAME` and `PUBLISH_PASSWORD`: Sonatype credentials for publishing.

## Releasing from the local setup

Releasing from the local setup can be done providing the previously mentioned four credential values, i.e.
`PUBLISH_USERNAME`, `PUBLISH_PASSWORD`

```sh
export PUBLISH_USERNAME=my_sonatype_user
export PUBLISH_PASSWORD=my_sonatype_key
export RELEASE_VERSION=2.4.5 # Set version you want to release
./gradlew build final -Prelease.version=${RELEASE_VERSION}
```
