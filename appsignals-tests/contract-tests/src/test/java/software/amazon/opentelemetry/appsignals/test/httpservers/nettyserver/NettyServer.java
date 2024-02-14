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

package software.amazon.opentelemetry.appsignals.test.httpservers.nettyserver;

import static io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_SERVER;
import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.proto.common.v1.KeyValue;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.opentelemetry.appsignals.test.httpservers.base.BaseHttpServerTest;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeSpan;
import software.amazon.opentelemetry.appsignals.test.utils.SemanticConventionsConstants;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class NettyServer extends BaseHttpServerTest {
  @Override
  protected String getApplicationImageName() {
    return "aws-appsignals-tests-http-server-netty-server";
  }

  @Override
  protected String getApplicationWaitPattern() {
    return ".*Started Application.*";
  }

  @Test
  void testSuccess() {
    doTestSuccess();
  }

  @Test
  void testError() {
    doTestError();
  }

  @Test
  void testFault() {
    doTestFault();
  }

  @Override
  protected void assertSemanticConventionsSpanAttributes(
      List<ResourceScopeSpan> resourceScopeSpans,
      String method,
      String route,
      String path,
      long status_code) {
    assertThat(resourceScopeSpans)
        .satisfiesOnlyOnce(
            rss -> {
              assertThat(rss.getSpan().getKind()).isEqualTo(SPAN_KIND_SERVER);
              // Netty does not have support to routes, therefore the name of the span should be
              // only the HTTP Method according to the spec
              // https://github.com/open-telemetry/opentelemetry-specification/blob/v1.20.0/specification/trace/semantic_conventions/http.md#name
              assertThat(rss.getSpan().getName()).isEqualTo(method);
              var attributesList = rss.getSpan().getAttributesList();
              assertSemanticConventionsAttributes(attributesList, method, route, path, status_code);
            });
  }

  /**
   * This method is overriding base class Semantic Conventions Attributes Assertions method to
   * remove HTTP_ROUTE assertion as it is not supported by Netty Framework.
   *
   * @param attributesList
   * @param method
   * @param route
   * @param target
   * @param status_code
   */
  @Override
  protected void assertSemanticConventionsAttributes(
      List<KeyValue> attributesList, String method, String route, String target, long status_code) {
    assertThat(attributesList)
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.SERVER_ADDRESS);
              assertThat(attribute.getValue().getStringValue()).isEqualTo("localhost");
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.SERVER_PORT);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.NET_PEER_ADDR);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.NET_PEER_PORT);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.URL_SCHEME);
              assertThat(attribute.getValue().getStringValue()).isEqualTo("http");
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.URL_TARGET);
              assertThat(attribute.getValue().getStringValue()).isEqualTo(target);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey())
                  .isEqualTo(SemanticConventionsConstants.HTTP_RESPONSE_STATUS_CODE);
              assertThat(attribute.getValue().getIntValue()).isEqualTo(status_code);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey())
                  .isEqualTo(SemanticConventionsConstants.HTTP_REQUEST_METHOD);
              assertThat(attribute.getValue().getStringValue()).isEqualTo(method);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey())
                  .isEqualTo(SemanticConventionsConstants.NET_PROTOCOL_VERSION);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.THREAD_ID);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.THREAD_NAME);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey())
                  .isEqualTo(SemanticConventionsConstants.USER_AGENT_ORIGINAL);
            });
  }
}
