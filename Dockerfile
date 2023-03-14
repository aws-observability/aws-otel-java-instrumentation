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
