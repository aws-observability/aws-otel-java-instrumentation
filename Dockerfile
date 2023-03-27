#
# Copyright Amazon.com, Inc. or its affiliates.
#
# Licensed under the Apache License, Version 2.0 (the "License").
# You may not use this file except in compliance with the License.
# A copy of the License is located at
#
#  http://aws.amazon.com/apache2.0
#
# or in the "license" file accompanying this file. This file is distributed
# on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing
# permissions and limitations under the License.

# Stage 1: Build the cp-utility binary
FROM rust:1.68 as builder

WORKDIR /usr/src/cp-utility
COPY ./tools/cp-utility .

# Validations
## Validate formatting
RUN rustup component add rustfmt
RUN cargo fmt --check

## Audit dependencies
RUN cargo install cargo-audit
RUN cargo audit

# Cross-compile based on the target platform.
## TARGETARCH is defined by buildx
# https://docs.docker.com/engine/reference/builder/#automatic-platform-args-in-the-global-scope
ARG TARGETARCH
RUN if [ $TARGETARCH = "amd64" ]; then export ARCH="x86_64" ; \
    elif [ $TARGETARCH = "arm64" ]; then export ARCH="aarch64" ; \
    else false; \
    fi \
    && rustup target add ${ARCH}-unknown-linux-musl \
    && cargo test  --target ${ARCH}-unknown-linux-musl \
    && cargo install --target ${ARCH}-unknown-linux-musl --path . --root .

# Stage 2: Create distribution
FROM scratch

ARG ADOT_JAVA_VERSION

COPY --from=builder /usr/src/cp-utility/bin/cp-utility /

COPY ./otelagent/build/libs/aws-opentelemetry-agent-${ADOT_JAVA_VERSION}.jar /javaagent.jar

ENTRYPOINT ["/cp-utility"]
