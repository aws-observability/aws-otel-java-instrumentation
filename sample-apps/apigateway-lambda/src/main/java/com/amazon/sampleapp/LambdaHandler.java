package com.amazon.sampleapp;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.io.IOException;
import java.util.Map;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class LambdaHandler implements RequestHandler<Object, Map<String, Object>> {

  private final OkHttpClient client = new OkHttpClient();
  private final S3Client s3Client = S3Client.create();

  @Override
  public Map<String, Object> handleRequest(Object input, Context context) {
    System.out.println("Executing LambdaHandler");

    // https://docs.aws.amazon.com/lambda/latest/dg/configuration-envvars.html#configuration-envvars-runtime
    // try and get the trace id from environment variable _X_AMZN_TRACE_ID. If it's not present
    // there
    // then try the system property.
    String traceId =
        System.getenv("_X_AMZN_TRACE_ID") != null
            ? System.getenv("_X_AMZN_TRACE_ID")
            : System.getProperty("com.amazonaws.xray.traceHeader");

    System.out.println("Trace ID: " + traceId);

    JSONObject responseBody = new JSONObject();
    responseBody.put("traceId", traceId);

    // Make a remote call using OkHttp
    System.out.println("Making a remote call using OkHttp");
    String url = "https://www.amazon.com";
    Request request = new Request.Builder().url(url).build();

    try (Response response = client.newCall(request).execute()) {
      responseBody.put("httpRequest", "Request successful");
    } catch (IOException e) {
      context.getLogger().log("Error: " + e.getMessage());
      responseBody.put("httpRequest", "Request failed");
    }
    System.out.println("Remote call done");

    // Make a S3 HeadBucket call to check whether the bucket exists
    System.out.println("Making a S3 HeadBucket call");
    String bucketName = "SomeDummyBucket";
    try {
      HeadBucketRequest headBucketRequest = HeadBucketRequest.builder().bucket(bucketName).build();
      s3Client.headBucket(headBucketRequest);
      responseBody.put("s3Request", "Bucket exists and is accessible: " + bucketName);
    } catch (S3Exception e) {
      if (e.statusCode() == 403) {
        responseBody.put("s3Request", "Access denied to bucket: " + bucketName);
      } else if (e.statusCode() == 404) {
        responseBody.put("s3Request", "Bucket does not exist: " + bucketName);
      } else {
        System.err.println("Error checking bucket: " + e.awsErrorDetails().errorMessage());
        responseBody.put(
            "s3Request", "Error checking bucket: " + e.awsErrorDetails().errorMessage());
      }
    }
    System.out.println("S3 HeadBucket call done");

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
