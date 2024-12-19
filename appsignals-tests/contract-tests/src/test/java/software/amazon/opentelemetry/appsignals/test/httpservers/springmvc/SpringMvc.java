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

package software.amazon.opentelemetry.appsignals.test.httpservers.springmvc;

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

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.proto.common.v1.KeyValue;
import java.util.List;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.opentelemetry.appsignals.test.httpservers.base.BaseHttpServerTest;
import software.amazon.opentelemetry.appsignals.test.utils.SemanticConventionsConstants;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpringMvc extends BaseHttpServerTest {
  @Override
  protected String getApplicationImageName() {
    return "aws-appsignals-tests-http-server-spring-mvc";
  }

  @Override
  protected String getApplicationWaitPattern() {
    return ".*Started Application.*";
  }

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
              assertThat(attribute.getKey())
                  .isEqualTo(SemanticConventionsConstants.NETWORK_PEER_ADDRESS);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey())
                  .isEqualTo(SemanticConventionsConstants.NETWORK_PEER_PORT);
              assertThat(attribute.getValue().getIntValue()).isBetween(1023L, 65536L);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.URL_SCHEME);
              assertThat(attribute.getValue().getStringValue()).isEqualTo("http");
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.HTTP_ROUTE);
              assertThat(attribute.getValue().getStringValue()).isEqualTo(route);
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
                  .isEqualTo(SemanticConventionsConstants.NETWORK_PROTOCOL_VERSION);
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

  //  @Test
  //  void testRoutes() {
  //    doTestRoutes("/users/{userId}/orders/{orderId}");
  //  }
  //
  //  @Test
  //  void testSuccess() {
  //    doTestSuccess();
  //  }
  //
  //  @Test
  //  void testError() {
  //    doTestError();
  //  }
  //
  //  @Test
  //  void testFault() {
  //    doTestFault();
  //  }
}
