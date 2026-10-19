FROM golang:1.16-alpine

ADD https://hey-release.s3.us-east-2.amazonaws.com/hey_linux_amd64 /usr/local/bin/hey

RUN chmod +x /usr/local/bin/hey

CMD hey -z "$TEST_DURATION_MINUTES"m http://"$TARGET_ADDRESS"/outgoing-http-call
