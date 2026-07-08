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

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The custom fetcher reads aws:autoscaling:groupName from IMDS instance tags (the stock OTel
 * Ec2Resource does not), so the SDK can resolve ec2:&lt;asg&gt; like the CloudWatch agent. A local
 * HTTP server stands in for IMDS.
 */
class Ec2AutoScalingGroupFetcherTest {

  private static final String TOKEN = "fake-token";
  private static final String TOKEN_PATH = "/latest/api/token";
  private static final String ASG_PATH =
      "/latest/meta-data/tags/instance/aws:autoscaling:groupName";

  private HttpServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  private String endpointFor(HttpHandler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", handler);
    server.start();
    return "127.0.0.1:" + server.getAddress().getPort();
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  @Test
  void fetchesAsgFromImdsTags() throws IOException {
    AtomicReference<String> seenToken = new AtomicReference<>();
    String endpoint =
        endpointFor(
            exchange -> {
              String path = exchange.getRequestURI().getPath();
              if ("PUT".equals(exchange.getRequestMethod()) && TOKEN_PATH.equals(path)) {
                respond(exchange, 200, TOKEN);
              } else if (ASG_PATH.equals(path)) {
                seenToken.set(exchange.getRequestHeaders().getFirst("X-aws-ec2-metadata-token"));
                respond(exchange, 200, "my-asg");
              } else {
                respond(exchange, 404, "");
              }
            });

    String asg = new Ec2AutoScalingGroupFetcher(endpoint).fetch();

    assertThat(asg).isEqualTo("my-asg");
    // The token from the PUT must be forwarded on the tag GET.
    assertThat(seenToken.get()).isEqualTo(TOKEN);
  }

  @Test
  void trimsWhitespaceFromAsgValue() throws IOException {
    String endpoint =
        endpointFor(
            exchange -> {
              if (ASG_PATH.equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 200, "  my-asg\n");
              } else {
                respond(exchange, 200, TOKEN);
              }
            });

    assertThat(new Ec2AutoScalingGroupFetcher(endpoint).fetch()).isEqualTo("my-asg");
  }

  @Test
  void emptyWhenTagsDisabled() throws IOException {
    // 404 on the tag path mimics instance metadata tags not being enabled.
    String endpoint =
        endpointFor(
            exchange -> {
              if (TOKEN_PATH.equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 200, TOKEN);
              } else {
                respond(exchange, 404, "");
              }
            });

    assertThat(new Ec2AutoScalingGroupFetcher(endpoint).fetch()).isEmpty();
  }

  @Test
  void emptyWhenNotOnEc2() {
    // Point at a closed port — connection refused, mimicking "not on EC2".
    assertThat(new Ec2AutoScalingGroupFetcher("127.0.0.1:1").fetch()).isEmpty();
  }
}
