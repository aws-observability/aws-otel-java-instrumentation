# AWS OpenTelemetry Java Instrumentation Release Process

## Preparing for a release

Before beginning a release, make sure [dependencies](https://github.com/aws-observability/aws-otel-java-instrumentation/blob/main/dependencyManagement/build.gradle.kts) are updated.

Run `./gradlew dependencyUpdates` to get a report on what dependencies have updates and apply them to
`dependencyManagement` and `settings.gradle.kts`.

Then, run `rm -rf licenses && ./gradlew --no-build-cache --rerun-tasks generateLicenseReport` to update our licenses to reflect the update. Send a PR
and merge.

## Starting the Release

If you are creating a new major/minor release, you first need to create a branch in this repository with the following convention: `release/v<major>.<minor>.x`. E.g.: `release/v1.21.x`.
It is not possible to release from the `main` branch, so this step cannot be skipped.

Additionally, create and push a release branch to the [aws-otel-test-framework](https://github.com/aws-observability/aws-otel-test-framework) repository using the following command:

`git fetch origin && git checkout origin/terraform && git checkout -b java-release/<version> && git push origin release/<version>`

NOTE: *The naming convention for the branch is java-release/<version> with the patch number replaced with x. So, if your release version is v0.1.0, then the branch should be java-release/v0.1.x.*

NOTE: *origin refers to the remote for https://github.com/aws-observability/aws-otel-test-framework.git. If the name of your remote ref is different adjust the command.*

After the release branches have been created in this repository and the [aws-otel-test-framework](https://github.com/aws-observability/aws-otel-test-framework) repository, open the release build workflow in your browser [here](https://github.com/aws-observability/aws-otel-java-instrumentation/actions?query=workflow%3A%22Release+Build%22).

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
1. Create a branch from the release that you want to patch. It should follow the convention `release/v<major>.<minor>.x`. E.g.: if you want to patch release 1.21.0, the name of the branch should be `release/v1.21.x`.
1. Modify the source code/dependencies. You can only update the patch version of opentelemetry dependencies.
1. Optionally prepare patches that can be applied to opentelemetry-java and opentelemetry-java-instrumentation. More details about this in the following section.
1. Create pull request to merge in the release branch.

After the pull request is merged, open the release build workflow in your browser [here](https://github.com/aws-observability/aws-otel-java-instrumentation/actions?query=workflow%3A%22Release+Build%22).

Select the branch and provide the version.

### Patching upstream dependencies

If you need to patch upstream dependencies, you need:

* Provide patch files for each repository that will need to be patched. These files should be located in `.github/patches/release/v<major>.<minor>.x` and should be named
using the convention `<repository name>.patch`. The following repositories are supported: opentelemetry-java, opentelemetry-java-instrumentation and opentelemetry-java-contrib. Provide one patch file per repository. The adot patch version of each upstream dependency should be `<version>-adot<number>` where `version` is the version of the upstream dependency and `number` is the number of this patch that should be incremented from 1 per patch version.

* Create a `versions` file in the directory `.github/patches/release/v<major>.<minor>.x`. This file should contain shell variables with the versions of the tags of the repositories which will receive patches.
  This file should define the following variables:
    * `OTEL_JAVA_VERSION`. Tag of the opentelemetry-java repository to use. E.g.: `JAVA_OTEL_JAVA_VERSION=v1.21.0`
    * `OTEL_JAVA_INSTRUMENTATION_VERSION`. Tag of the opentelemetry-java-instrumentation repository to use, e.g.: `OTEL_JAVA_INSTRUMENTATION_VERSION=v1.21.0`
    * `OTEL_JAVA_CONTRIB_VERSION`. Tag of the opentelemetry-java-contrib repository. E.g.: `OTEL_JAVA_CONTRIB_VERSION=v1.21.0`

During the build, ephemeral artifacts will be generated and stored into maven local and those will be used to build the ADOT Java Agent.

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
