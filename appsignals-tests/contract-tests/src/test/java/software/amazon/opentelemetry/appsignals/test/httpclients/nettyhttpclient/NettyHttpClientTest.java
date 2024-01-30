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

package software.amazon.opentelemetry.appsignals.test.httpclients.nettyhttpclient;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.opentelemetry.appsignals.test.httpclients.base.BaseHttpClientTest;
import software.amazon.opentelemetry.appsignals.test.utils.SemanticConventionsConstants;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NettyHttpClientTest extends BaseHttpClientTest {
  @Override
  protected String getApplicationImageName() {
    return "aws-appsignals-tests-netty-http-client-app";
  }

  @Override
  protected String getApplicationWaitPattern() {
    return ".*Started .*";
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
    System.out.println("Netty http Client is ");
    doTestFault();
  }

  @Test
  void testSuccessPost() {
    doTestSuccessPost();
  }

  @Test
  void testErrorPost() {
    doTestErrorPost();
  }

  @Test
  void testFaultPost() {
    doTestFaultPost();
  }

  @Override
  protected void assertSemanticConventionsAttributes(
      List<KeyValue> attributesList, String method, String endpoint, long status_code) {
    // System.out.println("Attribute list is: " + attributesList.toString());
    assertThat(attributesList)
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.NET_PEER_NAME);
              assertThat(attribute.getValue().getStringValue()).isEqualTo("backend");
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.NET_PEER_PORT);
              assertThat(attribute.getValue().getIntValue()).isEqualTo(8080L);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey())
                  .isEqualTo(SemanticConventionsConstants.HTTP_RESPONSE_STATUS_CODE);
              assertThat(attribute.getValue().getIntValue()).isEqualTo(status_code);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.URL_FULL);
              assertThat(attribute.getValue().getStringValue())
                  .isEqualTo(String.format("%s/%s", "http://backend:8080/backend", endpoint));
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
            });
  }
}
