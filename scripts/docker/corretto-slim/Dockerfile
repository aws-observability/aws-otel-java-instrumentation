FROM amazoncorretto/amazoncorretto:11-alpine-jre

RUN apk update && apk add libc6-compat ca-certificates && rm -rf /var/cache/apk/*

ENTRYPOINT ["java", "-jar"]
