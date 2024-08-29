# Introduction

This directory contain tests that are used exclusively for appsignals:
* Contract tests for semantic conventions.
* Contract tests for appsignals specific attributes.

# How it works?

The tests present here rely on the auto-instrumentation of a sample application which will send telemetry signals to
a mock collector. The tests will use the data collected by the mock collector to perform assertions and validate that
the contracts are being respected.


# Types of tested frameworks

The frameworks and libraries that are tested in the contract tests should fall in the following categories (more can be added on demand):
* http-servers - applications meant to test http servers.
* http-clients - applications meant to test http clients.
* aws-sdk - Applications meant to test the AWS SDK
* pub-sub - Asynchronous type of application where you typically have a publisher and a subscriber communicating using a message broker.

When testing a framework, we will create a sample application. The sample applications are stored following this
convention: `appsignals-tests/images/<application-type>/<framework-name>`

# Adding tests for a new library or framework

The steps to add a new test for a library or framework are:

* Create a sample application that can be run inside a docker image using Jib.
    * The sample application should be its own gradlew subproject. (you need to add it to `settings.gradle.kts` in the root of this project)
    * The sample application should be located in the directory `appsignals-tests/images/<application-type>/<framework-name>`
* Add a test class for the sample application.
    * The test class should created in `appsignals-tests/contract/tests`.
    * The name of the java package for the test classes should follow this convention: `software.amazon.opentelemetry.appsignals.test.<application-type>`

# How to run the tests locally?

Pre-requirements:
 * Ensure Docker is running on your machine.
 * Ensure AWS credentials are exported to environment variables.

From the root of this project execute:

```
## login to public ECR
aws ecr-public get-login-password --region us-east-1 | docker login --username AWS --password-stdin public.ecr.aws

# Run the patching script
./scripts/local_patch.sh

# Run the tests
./gradlew appsignals-tests:contract-tests:contractTests
```
