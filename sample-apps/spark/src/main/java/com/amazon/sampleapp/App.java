package com.amazon.sampleapp;

import io.opentelemetry.trace.TracingContextUtils;

import java.io.UncheckedIOException;
import java.util.Random;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.OkHttpClient;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;

import static spark.Spark.*;

public class App {
    static final String REQUEST_START_TIME = "requestStartTime";

    private static MetricEmitter buildMetricEmitter(){
        return new MetricEmitter();
    }

    public static void main(String[] args) {
        MetricEmitter metricEmitter = buildMetricEmitter();
        final Call.Factory httpClient = new OkHttpClient();
        final S3Client s3 = S3Client.builder().build();
        String port;
        String host;
        String listenAddress = System.getenv("LISTEN_ADDRESS");

        if (listenAddress == null) {
            host = "127.0.0.1";
            port = "4567";
        } else {
            String[] splitAddress = listenAddress.split(":");
            host = splitAddress[0];
            port = splitAddress[1];
        }

        // set sampleapp app port number and ip address
        port(Integer.parseInt(port));
        ipAddress(host);

        get("/", (req, res) -> {
            return "healthcheck";
        });

        /**
         * trace http request
         */
        get("/outgoing-http-call", (req, res) -> {
            try (Response response =
                         httpClient.newCall(new Request.Builder().url("https://aws.amazon.com").build()).execute()) {
            } catch (IOException e) {
                throw new UncheckedIOException("Could not fetch endpoint", e);
            }

            return getXrayTraceId();
        });

        /**
         * trace aws sdk request
         */
        get("/aws-sdk-call", (req, res) -> {
            s3.listBuckets();

            return getXrayTraceId();
        });


        /**
         * record a start time for each request
         */
        before((req, res) -> {
            req.attribute(REQUEST_START_TIME, System.currentTimeMillis());
        });

        after((req, res) -> {
            // for below paths we don't emit metric data
            if (req.pathInfo().equals("/outgoing-http-call")) {
                String statusCode = String.valueOf(res.status());
                // calculate return time
                Long requestStartTime = req.attribute(REQUEST_START_TIME);
                metricEmitter.emitReturnTimeMetric(
                        System.currentTimeMillis()- requestStartTime,
                        req.pathInfo(),
                        statusCode
                );

                // emit http request load size
                metricEmitter.emitBytesSentMetric(
                        req.contentLength() + mimicPayloadSize(),
                        req.pathInfo(),
                        statusCode);
            }
        });

        exception(Exception.class, (exception, request, response) -> {
            // Handle the exception here
            exception.printStackTrace();
        });
    }

    // get x-ray trace id
    private static String getXrayTraceId() {
        String traceId = TracingContextUtils.getCurrentSpan().getContext().getTraceIdAsHexString();
        String xrayTraceId = "1-" + traceId.substring(0, 8) + "-" + traceId.substring(8);

        return String.format("{\"traceId\": \"%s\"}", xrayTraceId);
    }

    private static int mimicPayloadSize() {
        Random randomGenerator = new Random();
        return randomGenerator.nextInt(1000);
    }
}
