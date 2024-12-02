package com.amazon.sampleapp;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.io.IOException;
import java.util.Map;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;

public class LambdaHandler implements RequestHandler<Object, Map<String, Object>> {

  private final OkHttpClient client = new OkHttpClient();

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

    // Make a remote call using OkHttp
    System.out.println("Making a remote call using OkHttp");
    String url = "https://www.amazon.com";
    Request request = new Request.Builder().url(url).build();

    JSONObject responseBody = new JSONObject();
    responseBody.put("traceId", traceId);

    try (Response response = client.newCall(request).execute()) {
      responseBody.put("httpRequest", "Request successful");
    } catch (IOException e) {
      context.getLogger().log("Error: " + e.getMessage());
      responseBody.put("httpRequest", "Request failed");
    }
    System.out.println("Remote call done");

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
