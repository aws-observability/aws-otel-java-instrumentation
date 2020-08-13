FROM amazoncorretto:11 as jrebuild

RUN jlink --output /jvm --no-header-files --no-man-pages --compress=2 --strip-debug --bind-services --add-modules \
java.base,\
java.compiler,\
java.datatransfer,\
java.desktop,\
java.instrument,\
java.logging,\
java.management,\
java.management.rmi,\
java.naming,\
java.net.http,\
java.prefs,\
java.rmi,\
java.scripting,\
java.se,\
java.security.jgss,\
java.security.sasl,\
java.smartcardio,\
java.sql,\
java.sql.rowset,\
java.transaction.xa,\
java.xml,\
java.xml.crypto,\
jdk.accessibility,\
jdk.aot,\
jdk.charsets,\
jdk.crypto.cryptoki,\
jdk.crypto.ec,\
jdk.dynalink,\
jdk.httpserver,\
jdk.internal.ed,\
jdk.internal.le,\
jdk.internal.vm.ci,\
jdk.internal.vm.compiler,\
jdk.internal.vm.compiler.management,\
jdk.jdwp.agent,\
jdk.jfr,\
jdk.jsobject,\
jdk.localedata,\
jdk.management,\
jdk.management.agent,\
jdk.management.jfr,\
jdk.naming.dns,\
jdk.naming.rmi,\
jdk.net,\
jdk.pack,\
jdk.scripting.nashorn,\
jdk.scripting.nashorn.shell,\
jdk.sctp,\
jdk.security.auth,\
jdk.security.jgss,\
jdk.unsupported,\
jdk.xml.dom,\
jdk.zipfs

FROM azul/zulu-openjdk:14 as build

ADD . /workspace
WORKDIR /workspace
RUN ./gradlew assemble

FROM gcr.io/distroless/java:11-debug AS deps

FROM gcr.io/distroless/cc:debug

RUN ["/busybox/sh", "-c", "ln -s /busybox/sh /bin/sh"]

COPY --from=deps /etc/ssl/certs/java /etc/ssl/certs/java
COPY --from=deps /lib/x86_64-linux-gnu/libz.so.1.2.8 /lib/x86_64-linux-gnu/libz.so.1.2.8
RUN ln -s /lib/x86_64-linux-gnu/libz.so.1.2.8 /lib/x86_64-linux-gnu/libz.so.1

COPY --from=jrebuild /jvm /usr/lib/jvm/java-11-amazon-corretto
COPY --from=build /workspace/otelagent/build/libs/aws-opentelemetry-agent-*.jar /aws-observability/

ENV JAVA_HOME=/usr/lib/jvm/java-11-amazon-corretto
RUN ln -s ${JAVA_HOME}/bin/java /usr/bin/java

ENTRYPOINT ["/usr/bin/java", "-jar"]
