# AWS Distro for OpenTelemetry - Instrumentation for Java

## Introduction

This project is a redistribution of the [OpenTelemetry Agent for Java](https://github.com/open-telemetry/opentelemetry-java-instrumentation),
preconfigured for use with AWS services. Please check out that project too to get a better
understanding of the underlying internals. You won't see much code in this repository since we only
apply some small configuration changes, and our OpenTelemetry friends takes care of the rest.

We provided a Java agent JAR that can be attached to any Java 8+ application and dynamically injects 
bytecode to capture telemetry from a number of popular libraries and frameworks. The telemetry data 
can be exported in a variety of formats. In addition, the agent and exporter can be configured via 
command line arguments or environment variables. The net result is the ability to gather telemetry
data from a Java application without any code changes.

## Getting Started

Check out the [getting started documentation](https://aws-otel.github.io/docs/getting-started/java-sdk/auto-instr).

## Supported Java libraries and frameworks

For the complete list of supported frameworks, please refer to the [OpenTelemetry for Java documentation](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/master/README.md#supported-java-libraries-and-frameworks).

## How it works

The [OpenTelemetry Java SDK](https://github.com/open-telemetry/opentelemetry-java) provides knobs
for configuring aspects using Java [SPI](https://docs.oracle.com/javase/tutorial/sound/SPI-intro.html).
This configuration includes being able to reconfigure the [IdsGenerator](https://github.com/open-telemetry/opentelemetry-java-contrib/blob/ed5c91ea2ea0cfd36b77c1f871c540ceba1c057b/aws-xray/src/main/java/io/opentelemetry/contrib/awsxray/AwsXrayIdGenerator.java)
which we need to support X-Ray compatible trace IDs. Because the SDK uses SPI, it is sufficient for
the custom implementation to be on the classpath to be recognized. The AWS distribution of the
OpenTelemetry Java Agent repackages the upstream agent by simply adding our SPI implementation for
reconfiguring the ID generator. In addition, it includes [AWS resource providers](https://github.com/open-telemetry/opentelemetry-java-contrib/tree/main/aws-resources/src/main/java/io/opentelemetry/contrib/aws/resource) 
by default, and it sets a system property to configure the agent to use multiple trace ID propagators, 
defaulting to maximum interoperability.

Other than that, the distribution is identical to the upstream agent and all configuration can be
used as is.

## Standardized Sample Applications 

In addition to the sample apps in this repository, there are also a set of [standardized sample applications](https://github.com/aws-observability/aws-otel-community/tree/master/sample-apps) that can be used. You can find the standardized Java sample app [here](https://github.com/aws-observability/aws-otel-community/tree/master/sample-apps/java-sample-app).

## Support

Please note that as per policy, we're providing support via GitHub on a best effort basis. However, if you have AWS Enterprise Support you can create a ticket and we will provide direct support within the respective SLAs.

## Security issue notifications
If you discover a potential security issue in this project we ask that you notify AWS/Amazon Security via our [vulnerability reporting page](http://aws.amazon.com/security/vulnerability-reporting/). Please do **not** create a public github issue.

before spans data!!!!!!!: 
[SpanData{
spanContext=ImmutableSpanContext{
traceId=674e4054ae4f6dc29558bb93e01c88aa, 
spanId=b3e242267283ef6e, 
traceFlags=01, 
traceState=ArrayBasedTraceState{
entries=[]}, 
remote=false, 
valid=true}, 
parentSpanContext=ImmutableSpanContext{
traceId=00000000000000000000000000000000, 
spanId=0000000000000000, 
traceFlags=00, 
traceState=ArrayBasedTraceState{
entries=[]}, 
remote=false, 
valid=false}, 
resource=Resource{
schemaUrl=https://opentelemetry.io/schemas/1.24.0, 
attributes={
aws.local.service="aws-sdk-v1", 
container.id="5c1a417aec8a09b7a1e687a8a8554292f7a7d383817aaf8dddd07bdacb8e691b", 
host.arch="amd64", 
host.name="5c1a417aec8a", 
os.description="Linux 5.10.228-198.884.amzn2int.x86_64", 
os.type="linux", 
process.command_line="/usr/lib/jvm/java-21-amazon-corretto/bin/java 
-javaagent:/opentelemetry-javaagent-all.jar com.amazon.sampleapp.App", 
process.executable.path="/usr/lib/jvm/java-21-amazon-corretto/bin/java", 
process.pid=1, 
process.runtime.description="Amazon.com Inc. OpenJDK 64-Bit Server VM 21.0.5+11-LTS", 
process.runtime.name="OpenJDK Runtime Environment", 
process.runtime.version="21.0.5+11-LTS", 
service.instance.id="ccb7b47f-ccd5-4cc2-b32c-034b5804694f", 
service.name="aws-sdk-v1", 
telemetry.distro.name="opentelemetry-java-instrumentation", 
telemetry.distro.version="1.33.0-aws-SNAPSHOT", 
telemetry.sdk.language="java", 
telemetry.sdk.name="opentelemetry", 
telemetry.sdk.version="1.44.1"}}, 
instrumentationScopeInfo=InstrumentationScopeInfo{
name=io.opentelemetry.jetty-8.0, 
version=2.10.0-alpha, 
schemaUrl=null, 
attributes={}}, 
name=GET /s3/createbucket/:bucketname, 
kind=SERVER, 
startEpochNanos=1733181524709878370, 
endEpochNanos=1733181527659649586, 
attributes=AttributesMap{data={
http.route=/s3/createbucket/:bucketname, 
server.port=33887, 
http.request.method=GET, 
url.path=/s3/createbucket/create-bucket, h
ttp.response.status_code=200, 
network.peer.address=172.22.0.1, 
server.address=localhost, 
client.address=172.22.0.1, 
thread.id=53, 
url.scheme=http, 
thread.name=qtp901050607-53, 
network.protocol.version=1.1, 
network.peer.port=55828, 
user_agent.original=armeria/1.26.4}, 
capacity=128, 
totalAddedValues=14}, 
totalAttributeCount=14, 
events=[], 
totalRecordedEvents=0, 
links=[], 
totalRecordedLinks=0, 
status=ImmutableStatusData{statusCode=UNSET, description=}, 
hasEnded=true}]


after modifiedSpans data!!!!!!!:
[DelegatingSpanData{spanContext=ImmutableSpanContext{
traceId=674e4054ae4f6dc29558bb93e01c88aa, 
spanId=b3e242267283ef6e, 
traceFlags=01, 
traceState=ArrayBasedTraceState{
entries=[]}, 
remote=false, 
valid=true}, 
parentSpanContext=ImmutableSpanContext{
traceId=00000000000000000000000000000000, 
spanId=0000000000000000, 
traceFlags=00, 
traceState=ArrayBasedTraceState{
entries=[]}, 
remote=false, valid=false}, 
resource=Resource{
schemaUrl=https://opentelemetry.io/schemas/1.24.0, 
attributes={aws.local.service="aws-sdk-v1", 
container.id="5c1a417aec8a09b7a1e687a8a8554292f7a7d383817aaf8dddd07bdacb8e691b", 
host.arch="amd64", 
host.name="5c1a417aec8a", 
os.description="Linux 5.10.228-198.884.amzn2int.x86_64", 
os.type="linux", 
process.command_line="/usr/lib/jvm/java-21-amazon-corretto/bin/java 
-javaagent:/opentelemetry-javaagent-all.jar com.amazon.sampleapp.App", 
process.executable.path="/usr/lib/jvm/java-21-amazon-corretto/bin/java", 
process.pid=1, 
process.runtime.description="Amazon.com Inc. OpenJDK 64-Bit Server VM 21.0.5+11-LTS", 
process.runtime.name="OpenJDK Runtime Environment", 
process.runtime.version="21.0.5+11-LTS", 
service.instance.id="ccb7b47f-ccd5-4cc2-b32c-034b5804694f", 
service.name="aws-sdk-v1", 
telemetry.distro.name="opentelemetry-java-instrumentation", 
telemetry.distro.version="1.33.0-aws-SNAPSHOT", 
telemetry.sdk.language="java", 
telemetry.sdk.name="opentelemetry", 
telemetry.sdk.version="1.44.1"}}, 
instrumentationScopeInfo=InstrumentationScopeInfo{
name=io.opentelemetry.jetty-8.0, 
version=2.10.0-alpha, 
schemaUrl=null, 
attributes={}}, 
name=GET /s3/createbucket/:bucketname, 
kind=SERVER, 
startEpochNanos=1733181524709878370, 
attributes={
aws.local.operation="GET /s3/createbucket/:bucketname", 
aws.local.service="aws-sdk-v1", 
aws.span.kind="LOCAL_ROOT", 
client.address="172.22.0.1", 
http.request.method="GET", 
http.response.status_code=200, 
http.route="/s3/createbucket/:bucketname", 
network.peer.address="172.22.0.1", 
network.peer.port=55828, 
network.protocol.version="1.1", 
server.address="localhost", 
server.port=33887, 
thread.id=53, 
thread.name="qtp901050607-53", 
url.path="/s3/createbucket/create-bucket", 
url.scheme="http", 
user_agent.original="armeria/1.26.4"}, 
events=[], links=[], 
status=ImmutableStatusData{statusCode=UNSET, description=}, 
endEpochNanos=1733181527659649586, 
hasEnded=true, 
totalRecordedEvents=0, 
totalRecordedLinks=0, 
totalAttributeCount=17}]