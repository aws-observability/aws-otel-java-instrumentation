package com.amazon.sampleapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.springframework.context.annotation.Bean;
import software.opentelemetry.exporters.otlp.udp.OtlpUdpSpanExporter;
import software.opentelemetry.exporters.otlp.udp.OtlpUdpSpanExporterBuilder;

@SpringBootApplication
public class ValidationApp {

    @Bean
    public SdkTracerProvider tracerProvider() {
        // Set up UDP exporter
        OtlpUdpSpanExporter exporter = new OtlpUdpSpanExporterBuilder()
            .setEndpoint("*********:2000")
            .build();

        // Set up and return the tracer provider
        return SdkTracerProvider.builder()
            .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
            .build();
    }

    @Bean
    public OpenTelemetry openTelemetry(SdkTracerProvider tracerProvider) {
        // Set up OpenTelemetry using the tracer provider bean
        return OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .buildAndRegisterGlobal();
    }

    public static void main(String[] args) {
        SpringApplication.run(ValidationApp.class, args);
    }
}
