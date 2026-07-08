/*
 * Copyright Amazon.com, Inc. or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.opentelemetry.javaagent.providers.environment;

import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Fetches the EC2 Auto Scaling group name from IMDS instance tags.
 *
 * <p>The stock OTel {@code Ec2Resource} only reads the instance-identity document — it does NOT
 * fetch instance tags, so the Auto Scaling group is absent from the SDK Resource. The CloudWatch
 * agent reads the ASG from IMDS instance tags ({@code
 * /latest/meta-data/tags/instance/aws:autoscaling:groupName}) to resolve {@code ec2:<asg>}. This
 * fetcher closes that gap so the SDK can compute the same {@code aws.local.environment} the agent
 * would on EC2, without depending on the agent.
 *
 * <p>Instance metadata tags must be enabled on the instance ({@code InstanceMetadataTags=enabled});
 * when they aren't (or IMDS is unreachable / not EC2), the fetcher returns an empty string and the
 * {@link EnvironmentResolver} falls back to {@code ec2:default} — matching the agent.
 *
 * <p>The fetch is invoked lazily by the resolver only on the EC2 branch, so EKS/ECS/explicit-env
 * workloads never pay the IMDS round-trip.
 */
public final class Ec2AutoScalingGroupFetcher {

  private static final Logger logger = Logger.getLogger(Ec2AutoScalingGroupFetcher.class.getName());

  private static final String DEFAULT_IMDS_ENDPOINT = "169.254.169.254";
  private static final String TOKEN_PATH = "/latest/api/token";
  private static final String ASG_TAG_PATH =
      "/latest/meta-data/tags/instance/aws:autoscaling:groupName";
  private static final Duration TIMEOUT = Duration.ofSeconds(1);
  private static final RequestBody EMPTY_BODY = RequestBody.create(new byte[0]);

  private final String endpoint;

  public Ec2AutoScalingGroupFetcher() {
    // Endpoint is overridable for tests via the same system property the OTel EC2 resource uses;
    // defaults to the EC2 link-local IMDS address.
    this(System.getProperty("otel.aws.imds.endpointOverride", DEFAULT_IMDS_ENDPOINT));
  }

  // Visible for testing.
  Ec2AutoScalingGroupFetcher(String endpoint) {
    this.endpoint = endpoint;
  }

  /**
   * Returns the Auto Scaling group name from IMDS instance tags, or an empty string when not on
   * EC2, IMDS is unreachable, or instance metadata tags are not enabled. Never throws.
   */
  public String fetch() {
    try {
      OkHttpClient client =
          new OkHttpClient.Builder()
              .callTimeout(TIMEOUT)
              .connectTimeout(TIMEOUT)
              .readTimeout(TIMEOUT)
              .build();

      String urlBase = "http://" + endpoint;
      String token = fetchToken(client, urlBase + TOKEN_PATH);
      String asg = fetchTag(client, urlBase + ASG_TAG_PATH, token);
      return asg == null ? "" : asg.trim();
    } catch (RuntimeException e) {
      // Not on EC2, IMDS unreachable, or instance tags not enabled — all expected, non-fatal.
      logger.log(Level.FINE, "EC2 Auto Scaling group not available via IMDS", e);
      return "";
    }
  }

  private static String fetchToken(OkHttpClient client, String tokenUrl) {
    Request request =
        new Request.Builder()
            .url(tokenUrl)
            .method("PUT", EMPTY_BODY)
            .addHeader("X-aws-ec2-metadata-token-ttl-seconds", "60")
            .build();
    return execute(client, request);
  }

  private static String fetchTag(OkHttpClient client, String tagUrl, String token) {
    Request.Builder builder = new Request.Builder().url(tagUrl).get();
    if (!token.isEmpty()) {
      builder.addHeader("X-aws-ec2-metadata-token", token);
    }
    return execute(client, builder.build());
  }

  private static String execute(OkHttpClient client, Request request) {
    try (Response response = client.newCall(request).execute()) {
      if (response.code() != 200 || response.body() == null) {
        return "";
      }
      return response.body().string();
    } catch (Exception e) {
      logger.log(Level.FINE, "IMDS request failed: " + request.url(), e);
      return "";
    }
  }
}
