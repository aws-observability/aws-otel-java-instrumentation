package com.amazon.sampleapp;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.contrib.awsxray.AwsXrayIdGenerator;
import io.opentelemetry.contrib.awsxray.AwsXrayRemoteSampler;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.extension.aws.AwsXrayPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.s3.S3Client;

@SpringBootApplication
public class DemoApplication {

  @Bean
  public Call.Factory httpClient() {
    return new OkHttpClient();
  }

  @Bean
  public S3Client s3() {
    return S3Client.builder().build();
  }

  public static Resource resource = Resource.builder().build();

  public static OpenTelemetry openTelemetry =
      OpenTelemetrySdk.builder()
          .setPropagators(
              ContextPropagators.create(
                  TextMapPropagator.composite(
                      W3CTraceContextPropagator.getInstance(), AwsXrayPropagator.getInstance())))
          .setTracerProvider(
              SdkTracerProvider.builder()
                  .addSpanProcessor(
                      BatchSpanProcessor.builder(OtlpGrpcSpanExporter.getDefault()).build())
                  .setResource(resource)
                  .setSampler(
                      AwsXrayRemoteSampler.newBuilder(resource)
                          .setPollingInterval(Duration.ofSeconds(1))
                          .build())
                  .setIdGenerator(AwsXrayIdGenerator.getInstance())
                  .build())
          .buildAndRegisterGlobal();

  public static void main(String[] args) {
    // listenAddress should consist host + port (e.g. 127.0.0.1:5000)
    String port;
    String host;
    String listenAddress = System.getenv("LISTEN_ADDRESS");

    if (listenAddress == null) {
      host = "127.0.0.1";
      port = "8080";
    } else {
      String[] splitAddress = listenAddress.split(":");
      host = splitAddress[0];
      port = splitAddress[1];
    }

    Map<String, Object> config = new HashMap<String, Object>();
    config.put("server.address", host);
    config.put("server.port", port);

    SpringApplication app = new SpringApplication(DemoApplication.class);
    app.setDefaultProperties(config);
    app.run(args);
  }
}
