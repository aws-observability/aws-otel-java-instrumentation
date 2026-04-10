# Changelog

All notable changes to this project will be documented in this file.

> **Note:** This CHANGELOG was created starting after version 2.11.5. Earlier changes are not documented here.

For any change that affects end users of this package, please add an entry under the **Unreleased** section. Briefly summarize the change and provide the link to the PR. Example:

- add SigV4 authentication for HTTP exporter
  ([#1019](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1019))

If your change does not need a CHANGELOG entry, add the "skip changelog" label to your PR.

## Unreleased

- Support environment-configured endpoint visibility for HTTP operation names
  ([#1352](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1352))
- Bump Netty to 4.1.132.Final to fix CVE-2026-33870 and CVE-2026-33871
  ([#1348](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1348))

## v2.26.1 - 2026-03-27

- Bump OpenTelemetry Java Instrumentation version to 2.26.1
  ([#1342](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1342))
- End support for ADOT Java 1.x: remove v1 image scans and update README#1339
  ([#1339](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1339))
- Bump Netty to 4.1.132.Final to fix CVE-2026-33870 and CVE-2026-33871
  ([#1347](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1347))

## v2.25.1 - 2026-03-11

- feat: Allow disabling of default anomaly condition
  ([#1329](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1329))
- Upgrade jackson-bom to 2.21.1 to fix CVE GHSA-72hv-8253-57qq
  ([#1334](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1334))

## v2.25.0 - 2026-02-27

- Add adaptive sampling local config attribute to spans
  ([#1299](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1299))
- Optimize GetSamplingTargets calls by removing empty statistics documents
  ([#1298](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1298))
- feat: Allow disabling of default anomaly condition
  ([#1329](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1329))
- Upgrade jackson-bom to 2.21.1 to fix CVE GHSA-72hv-8253-57qq
  ([#1334](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1334))

## v2.23.0 - 2026-01-24

### Enhancements
- Upgrade to OTel v2.23.0 and Contrib v1.52.0
  ([#1292](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1292))
- Adaptive Sampling: Ensure the highest priority sampling rule is matched
  ([#1290](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1290))
- Sign Lambda layer by AWS Signer
  ([#1275](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1275))
- Bump Netty version to 4.1.130 Final
  ([#1271](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1271))
- Remove conflicting attributes from aws-sdk instrumentation
  ([#1294](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1294))
- Remove support for Java 23, add for Java 25
  ([#1296](https://github.com/aws-observability/aws-otel-java-instrumentation/pull/1296))
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
