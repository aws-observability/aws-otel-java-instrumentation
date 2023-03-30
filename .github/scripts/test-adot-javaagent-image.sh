#!/bin/bash
# Enable debug mode, fail on any command that fail in this script and fail on unset variables
set -x -e -u

TEST_TAG=$1
ADOT_JAVA_VERSION=$2

docker volume create operator-volume
docker run --mount source=operator-volume,dst=/otel-auto-instrumentation ${TEST_TAG} cp /javaagent.jar /otel-auto-instrumentation/javaagent.jar
docker run -dt --mount source=operator-volume,dst=/otel-auto-instrumentation --name temp  public.ecr.aws/amazonlinux/amazonlinux:latest
FILENAME=$(docker exec temp /bin/bash -c "ls /otel-auto-instrumentation")
if [ $FILENAME = javaagent.jar ]; then
  echo "javaagent.jar file was copied to the operator-volume"
else
  echo "error: javaagent.jar file was not copied to the operator-volume"
  exit 1;
fi

ORIG_CHECKSUM=$(sha256sum otelagent/build/libs/aws-opentelemetry-agent-${ADOT_JAVA_VERSION}.jar | cut -d' ' -f1)
CHECKSUM=$(docker exec temp /bin/bash -c "sha256sum /otel-auto-instrumentation/javaagent.jar | cut -d' ' -f1")
if [ $CHECKSUM = $ORIG_CHECKSUM ]; then
  echo "copied javaagent.jar checksum matched"
else
  echo "error: copied javaagent.jar checksum mis-matched"
  exit 1;
fi