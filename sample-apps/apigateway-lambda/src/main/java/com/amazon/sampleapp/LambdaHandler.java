package com.amazon.sampleapp;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;

public class LambdaHandler implements RequestHandler<Object, String> {
  private final OkHttpClient httpClient;
  private final S3Client s3Client;

  public LambdaHandler() {
    this.httpClient = new OkHttpClient();
    this.s3Client = S3Client.create();
  }

  @Override
  public String handleRequest(Object o, Context context) {
    makeRemoteCall();
    listS3Buckets();

    // Get the _X_AMZN_TRACE_ID environment variable
    String traceId = System.getenv("_X_AMZN_TRACE_ID");

    // Construct the response string
    return "Trace ID: " + traceId;
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
