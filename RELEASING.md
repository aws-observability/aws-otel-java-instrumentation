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

`git fetch origin && git checkout origin/terraform  -b java-release/<version> && git push origin java-release/<version>`

NOTE: *The naming convention for the branch is java-release/<version> with the patch number replaced with x. So, if your release version is v0.1.0, then the branch should be java-release/v0.1.x.*

NOTE: *origin refers to the remote for https://github.com/aws-observability/aws-otel-test-framework.git. If the name of your remote ref is different adjust the command.*

After the release branches have been created in this repository and the [aws-otel-test-framework](https://github.com/aws-observability/aws-otel-test-framework) repository, open the release build workflow in your browser [here](https://github.com/aws-observability/aws-otel-java-instrumentation/actions?query=workflow%3A%22Release+Build%22).

You will see a button that says "Run workflow". Press the button, enter the version number you want
to release in the input field that pops up, and then press "Run workflow".

To rehearse a release first, check the **dry run** checkbox (it is unchecked by default). A dry run needs the `Release` approval and then runs
`build-sdk` and `build-layer` only:
- builds and tests the agent and the Lambda layer
- signs and uploads the Maven Central deployment to staging, waits until Sonatype reports it
  `VALIDATED`, then drops it
- builds the multi-arch image as an OCI archive and tests it

It publishes nothing: no ECR image, Maven Central version, git tag, Lambda layer or GitHub release.
We recommend a dry run before each release. When it passes, run the workflow again with **dry run** unchecked.

A real release builds and validates everything in `build-sdk` first. Only then does `publish-sdk`
push the tested image to ECR, sign it, publish the validated Maven Central deployment and push the
git tag. After that, the Lambda layer is published and the GitHub release is drafted.

**Retries:** if a real release fails partway, run the workflow again with the same version.
- Anything already published is detected and skipped: the ECR image, the Maven Central version and
  the git tag.
- An ECR tag that already exists is never overwritten.
- A staged Maven Central deployment that wasn't published is dropped automatically.

  If a run is cancelled between `build-sdk` and `publish-sdk`, drop the leftover staging repository
  by hand: `.github/scripts/maven-central-deployment.sh drop <repository_key>`.

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

* Provide patch files for each repository that will need to be patched. These files should be located in `.github/patches` and should be named
using the convention `<repository name>.patch`. The following repositories are supported: opentelemetry-java, opentelemetry-java-instrumentation and opentelemetry-java-contrib. Provide one patch file per repository. The adot patch version of each upstream dependency should be `<version>-adot<number>` where `version` is the version of the upstream dependency and `number` is the number of this patch that should be incremented from 1 per patch version.

* Create a `versions` file in the directory `.github/patches/release`. This file should contain shell variables with the versions of the tags of the repositories which will receive patches.
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

* `PUBLISH_TOKEN_USERNAME` and `PUBLISH_TOKEN_PASSWORD`: Sonatype credentials for publishing.

## Releasing from the local setup

Releasing from the local setup can be done providing the previously mentioned four credential values, i.e.
`PUBLISH_TOKEN_USERNAME`, `PUBLISH_TOKEN_PASSWORD`

```sh
export PUBLISH_TOKEN_USERNAME=my_sonatype_user
export PUBLISH_TOKEN_PASSWORD=my_sonatype_key
export RELEASE_VERSION=2.4.5 # Set version you want to release
./gradlew build final -Prelease.version=${RELEASE_VERSION}
```
