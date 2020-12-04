# AWS OpenTelemetry Java Instrumentation Release Process

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
generated draft release to write release notes about the new release and add release notes.

You can use `git log upstream/v$MAJOR.$((MINOR-1)).x..upstream/v$MAJOR.$MINOR.x --graph --first-parent`
or the Github [compare tool](https://github.com/open-telemetry/opentelemetry-java/compare/)
to view a summary of all commits since last release as a reference.

## Patch Release

All patch releases should include only bug-fixes, and must avoid
adding/modifying the public APIs. 

Open the patch release build workflow in your browser [here](https://github.com/aws-observability/aws-otel-java-instrumentation/actions?query=workflow%3A%22Patch+Release+Build%22).

You will see a button that says "Run workflow". Press the button, enter the version number you want
to release in the input field for version that pops up and the commits you want to cherrypick for the
patch as a comma-separated list. Then, press "Run workflow".

If the commits cannot be cleanly applied to the release branch, for example because it has diverged
too much from main, then the workflow will fail before building. In this case, you will need to
prepare the release branch manually.

This example will assume patching into release branch `v1.2.x` from a git repository with remotes
named `origin` and `upstream`.

```
$ git remote -v
origin	git@github.com:username/opentelemetry-java.git (fetch)
origin	git@github.com:username/opentelemetry-java.git (push)
upstream	git@github.com:open-telemetry/opentelemetry-java.git (fetch)
upstream	git@github.com:open-telemetry/opentelemetry-java.git (push)
```

First, checkout the release branch

```
git fetch upstream v1.2.x
git checkout upstream/v1.2.x
```

Apply cherrypicks manually and commit. It is ok to apply multiple cherrypicks in a single commit.
Use a commit message such as "Manual cherrypick for commits commithash1, commithash2".

After commiting the change, push to your fork's branch.

```
git push origin v1.2.x
```

Create a PR to have code review and merge this into upstream's release branch. As this was not
applied automatically, we need to do code review to make sure the manual cherrypick is correct.

After it is merged, Run the patch release workflow again, but leave the commits input field blank.
The release will be made with the current state of the release branch, which is what you prepared
above.

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
