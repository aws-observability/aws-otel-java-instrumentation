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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.opentelemetry.appsignals.test.httpclients.base.BaseHttpClientTest;

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
}
