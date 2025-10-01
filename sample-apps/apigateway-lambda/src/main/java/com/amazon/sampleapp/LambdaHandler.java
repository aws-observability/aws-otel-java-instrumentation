package com.amazon.sampleapp;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import org.json.JSONObject;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class LambdaHandler implements RequestHandler<Object, Map<String, Object>> {

  private static final Logger logger = Logger.getLogger(LambdaHandler.class.getName());
  HttpClient client = HttpClient.newHttpClient();
  private final S3Client s3Client = S3Client.create();

  @Override
  public Map<String, Object> handleRequest(Object input, Context context) {
    logger.info("Executing LambdaHandler");

    // https://docs.aws.amazon.com/lambda/latest/dg/configuration-envvars.html#configuration-envvars-runtime
    // try and get the trace id from environment variable _X_AMZN_TRACE_ID. If it's not present
    // there
    // then try the system property.
    String traceId =
        System.getenv("_X_AMZN_TRACE_ID") != null
            ? System.getenv("_X_AMZN_TRACE_ID")
            : System.getProperty("com.amazonaws.xray.traceHeader");

    logger.info("Trace ID: " + traceId);

    JSONObject responseBody = new JSONObject();
    responseBody.put("traceId", traceId);

    // Make a remote call using OkHttp
    logger.info("Making a remote call using Java HttpClient");
    String url = "https://aws.amazon.com/";
    HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .build();
    try {
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      logger.info("Response status code: " + response.statusCode());
      responseBody.put("httpRequest", "Request successful");
    } catch (Exception e) {
      logger.severe("Error: " + e.getMessage());
      responseBody.put("httpRequest", "Request failed");
    }
    logger.info("Remote call done");

    // Make a S3 ListBuckets call to list the S3 buckets in the account
    logger.info("Making a S3 ListBuckets call");
    try {
      ListBucketsResponse listBucketsResponse = s3Client.listBuckets();
      responseBody.put("s3Request", "ListBuckets successful");
    } catch (S3Exception e) {
      logger.severe("Error listing buckets: " + e.awsErrorDetails().errorMessage());
      responseBody.put("s3Request", "Error listing buckets: " + e.awsErrorDetails().errorMessage());
    }
    logger.info("S3 ListBuckets call done");
    
    logger.info("Lambda function completed processing request successfully");

    // return a response in the ApiGateway proxy format
    return Map.of(
        "isBase64Encoded",
        false,
        "statusCode",
        200,
        "body",
        responseBody.toString(),
        "headers",
        Map.of("Content-Type", "application/json"));
  }
}
