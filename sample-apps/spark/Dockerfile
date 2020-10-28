FROM gradle:6.5.0-jdk11 as builder

# build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle build
RUN tar -xvf build/distributions/TraceEmitter.tar

FROM amazoncorretto:8
WORKDIR /app
COPY --from=builder /home/gradle/src/TraceEmitter /app/
RUN ls -R

COPY entrypoint.sh /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
