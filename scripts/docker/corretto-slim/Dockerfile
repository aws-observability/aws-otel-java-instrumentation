FROM amazoncorretto/amazoncorretto:11-alpine-jre

RUN apk update && apk add libc6-compat && rm -rf /var/cache/apk/*

ENTRYPOINT ["java", "-jar"]
