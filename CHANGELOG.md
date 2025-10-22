# Changelog

All notable changes to this project will be documented in this file.

> **Note:** This CHANGELOG was created starting after version 2.11.5. Earlier changes are not documented here.

For any change that affects end users of this package, please add an entry under the **Unreleased** section. Briefly summarize the change and provide the link to the PR. Example:

- add SigV4 authentication for HTTP exporter
  ([#1019](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1019))

If your change does not need a CHANGELOG entry, add the "skip changelog" label to your PR.

## Unreleased

### Enhancements

- Support X-Ray Trace Id extraction from Lambda Context object, and respect user-configured OTEL_PROPAGATORS in AWS Lamdba instrumentation
  ([#1191](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1191)) ([#1218](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1218))
- Adaptive Sampling improvements: Ensure propagation of sampling rule across services and AWS accounts. Remove unnecessary B3 propagator.
  ([#1201](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1201))