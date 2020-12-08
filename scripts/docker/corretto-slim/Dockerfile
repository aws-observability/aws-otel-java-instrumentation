FROM amazoncorretto:11-alpine-jdk

# Copied from https://github.com/corretto/corretto-docker/blob/master/11/jre/alpine/Dockerfile
RUN jlink --endian little --release-info $JAVA_HOME/release \
            --add-modules "java.base,java.datatransfer,java.desktop,java.instrument,java.logging,java.management,java.management.rmi,java.naming,java.prefs,\
java.rmi,java.security.sasl,java.xml,jdk.internal.vm.ci,jdk.jfr,jdk.management,jdk.management.jfr,jdk.management.agent,jdk.net,jdk.sctp,jdk.unsupported,\
jdk.naming.rmi,java.compiler,jdk.aot,jdk.internal.vm.compiler,jdk.internal.vm.compiler.management,java.se,java.net.http,java.scripting,java.security.jgss,\
java.smartcardio,java.sql,java.sql.rowset,java.transaction.xa,java.xml.crypto,jdk.accessibility,jdk.charsets,jdk.crypto.cryptoki,jdk.crypto.ec,\
jdk.dynalink,jdk.httpserver,jdk.jsobject,jdk.localedata,jdk.naming.dns,jdk.scripting.nashorn,jdk.security.auth,jdk.security.jgss,jdk.xml.dom,jdk.zipfs,\
jdk.pack,jdk.scripting.nashorn.shell,jdk.jcmd,jdk.jfr" \
            --no-man-pages --no-header-files --strip-debug --output /temp/java-11-amazon-corretto

FROM alpine:3.12

COPY --from=0 /temp/java-11-amazon-corretto /usr/lib/jvm/java-11-amazon-corretto
COPY --from=0 /licenses /licenses

RUN apk update && apk add libc6-compat ca-certificates && rm -rf /var/cache/apk/*

ENV LANG C.UTF-8
ENV JAVA_HOME=/usr/lib/jvm/java-11-amazon-corretto
ENV PATH=$PATH:/usr/lib/jvm/java-11-amazon-corretto/bin

ENTRYPOINT ["java", "-jar"]
