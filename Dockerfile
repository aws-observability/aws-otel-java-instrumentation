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
FROM rust:1.68 as builder

# possible values: x86_64 | aarch64
ARG ARCH=x86_64

RUN rustup target add ${ARCH}-unknown-linux-musl
RUN rustup component add rustfmt
WORKDIR /usr/src/cp-utility
COPY ./tools/cp-utility .

RUN cargo fmt --check
RUN cargo test  --target ${ARCH}-unknown-linux-musl
RUN cargo install --target ${ARCH}-unknown-linux-musl --path . --root .

FROM scratch

ARG ADOT_JAVA_VERSION

COPY --from=builder /usr/src/cp-utility/bin/cp-utility /

COPY ./otelagent/build/libs/aws-opentelemetry-agent-${ADOT_JAVA_VERSION}.jar /javaagent.jar

ENTRYPOINT ["/cp-utility"]
