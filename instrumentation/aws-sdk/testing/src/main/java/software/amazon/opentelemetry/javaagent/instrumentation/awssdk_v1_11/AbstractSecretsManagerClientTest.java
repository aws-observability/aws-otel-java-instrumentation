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

package software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v1_11;

import com.amazonaws.services.secretsmanager.AWSSecretsManager;
import com.amazonaws.services.secretsmanager.AWSSecretsManagerClientBuilder;
import com.amazonaws.services.secretsmanager.model.CreateSecretRequest;
import com.google.common.collect.ImmutableMap;
import io.opentelemetry.testing.internal.armeria.common.HttpResponse;
import io.opentelemetry.testing.internal.armeria.common.HttpStatus;
import io.opentelemetry.testing.internal.armeria.common.MediaType;
import org.junit.jupiter.api.Test;

/*
 * This is a new class created during ADOT git patching.
 */
public abstract class AbstractSecretsManagerClientTest extends AbstractBaseAwsClientTest {

  public abstract AWSSecretsManagerClientBuilder configureClient(
      AWSSecretsManagerClientBuilder client);

  @Override
  protected boolean hasRequestId() {
    return true;
  }

  @Test
  public void sendRequestWithMockedResponse() throws Exception {
    AWSSecretsManagerClientBuilder clientBuilder = AWSSecretsManagerClientBuilder.standard();
    AWSSecretsManager client =
        configureClient(clientBuilder)
            .withEndpointConfiguration(endpoint)
            .withCredentials(credentialsProvider)
            .build();

    String body =
        "{"
            + "\"ARN\": \"arn:aws:secretsmanager:us-west-2:123456789012:secret:MyTestDatabaseSecret-a1b2c3\","
            + "\"Name\": \"MyTestDatabaseSecret\","
            + "\"VersionId\": \"EXAMPLE1-90ab-cdef-fedc-ba987SECRET1\""
            + "}";
    server.enqueue(HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, body));

    Object response =
        client.createSecret(
            new CreateSecretRequest().withName("secretName").withSecretString("secretValue"));

    assertRequestWithMockedResponse(
        response,
        client,
        "AWSSecretsManager",
        "CreateSecret",
        "POST",
        ImmutableMap.of(
            "aws.secretsmanager.secret.arn",
            "arn:aws:secretsmanager:us-west-2:123456789012:secret:MyTestDatabaseSecret-a1b2c3"));
  }
}
