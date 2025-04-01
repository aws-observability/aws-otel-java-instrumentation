# ADOT X-Ray OTLP Trace Export Configuration

This guide explains how to automatically configure ADOT environment variables for exporting traces to [AWS X-Ray OTLP endpoint](https://docs.aws.amazon.com/xray/latest/devguide/xray-opentelemetry.html)

## Pre-requisites:
1. Transaction Search must be enabled in order to send spans to the Xray OTLP endpoint. See [this doc](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch-Transaction-Search-getting-started.html) on how to enable Transaction Search.

2. Ensure the AWS IAM role used for credentials in your application environment has [AWSXRayWriteOnlyAccess](https://docs.aws.amazon.com/aws-managed-policy/latest/reference/AWSXrayWriteOnlyAccess.html) managed policy attached to it.

## Environment Variables

Set the following environment variables to **PROPERLY** configure trace export with SigV4 authentication:

```shell
# Set X-Ray endpoint (replace AWS_REGION with your region)
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=https://xray.<AWS_REGION>.amazonaws.com/v1/traces

# Configure OTLP export protocol
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf

OTEL_METRICS_EXPORTER=none
OTEL_LOGS_EXPORTER=none
OTEL_RESOURCE_ATTRIBUTES="service.name=<YOUR_SERVICE_NAME>"

# Disable application signals (they will be generated in CloudWatch backend)
OTEL_AWS_APPLICATION_SIGNALS_ENABLED=false
