# Changelog

All notable changes to this project will be documented in this file.

> **Note:** This CHANGELOG was created starting after version 2.11.5. Earlier changes are not documented here.

For any change that affects end users of this package, please add an entry under the **Unreleased** section. Briefly summarize the change and provide the link to the PR. Example:

- add SigV4 authentication for HTTP exporter
  ([#1019](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1019))

If your change does not need a CHANGELOG entry, add the "skip changelog" label to your PR.

## Unreleased

- Sign Lambda layer by AWS Signer
  ([#1275](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1275))
- Bump Netty version to 4.1.130 Final
  ([#1271](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1271))
- GetSamplingTargets statistics fixes and optimizations
  ([#1274](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1274))


### Enhancements

- Add Application Signals Dimensions to EMF exporter
  ([#1264](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1264))
- Configure EMF and CompactLog Exporters for Lambda Environment
  ([#1222](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1222))
- feat: [Java] EMF Exporter Implementation
  ([#1209](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1209))

## v2.20.0 - 2025-10-29

### Enhancements

- Support X-Ray Trace Id extraction from Lambda Context object, and respect user-configured OTEL_PROPAGATORS in AWS Lamdba instrumentation
  ([#1191](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1191)) ([#1218](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1218))
- Adaptive Sampling improvements: Ensure propagation of sampling rule across services and AWS accounts. Remove unnecessary B3 propagator.
  ([#1201](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1201))
- Add support for new formal database semantic convention keys.
  ([#1162](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1162))
- Bump ADOT Java version to 2.20.0 and OTel deps to 2.20.1.
  ([#1246](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1246))
