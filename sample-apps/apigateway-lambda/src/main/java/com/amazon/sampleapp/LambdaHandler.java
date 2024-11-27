package com.amazon.sampleapp;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.util.Map;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;

public class LambdaHandler implements RequestHandler<Object, Map<String, Object>> {
  private final OkHttpClient httpClient;
  private final S3Client s3Client;

  public LambdaHandler() {
    this.httpClient = new OkHttpClient();
    this.s3Client = S3Client.create();
  }

  @Override
  public Map<String, Object> handleRequest(Object o, Context context) {
    makeRemoteCall();
    listS3Buckets();

    // Get the trace id from system property
    // https://docs.aws.amazon.com/lambda/latest/dg/configuration-envvars.html#configuration-envvars-runtime
    String traceId = System.getProperty("com.amazonaws.xray.traceHeader");

    // Construct the response body
    JSONObject responseBody = new JSONObject();
    responseBody.put("message", "Request successful");
    responseBody.put("traceId", traceId);

    // Return the API Gateway-compatible response
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

  private void makeRemoteCall() {
    try {
      Request request = new Request.Builder().url("https://aws.amazon.com/").build();
      Response response = httpClient.newCall(request).execute();
      response.close();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private void listS3Buckets() {
    ListBucketsResponse response = s3Client.listBuckets();
    int bucketCount = response.buckets().size();

    // Print bucket count
    System.out.println("Number of S3 buckets: " + bucketCount);
  }
}
