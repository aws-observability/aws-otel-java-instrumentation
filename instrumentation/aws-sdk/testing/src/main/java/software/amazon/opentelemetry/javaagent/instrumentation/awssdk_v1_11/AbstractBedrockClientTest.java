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

import com.amazonaws.services.bedrock.AmazonBedrock;
import com.amazonaws.services.bedrock.AmazonBedrockClientBuilder;
import com.amazonaws.services.bedrock.model.GetGuardrailRequest;
import com.google.common.collect.ImmutableMap;
import io.opentelemetry.testing.internal.armeria.common.HttpResponse;
import io.opentelemetry.testing.internal.armeria.common.HttpStatus;
import io.opentelemetry.testing.internal.armeria.common.MediaType;
import org.junit.jupiter.api.Test;

/*
 * This is a new class created during ADOT git patching.
 */
public abstract class AbstractBedrockClientTest extends AbstractBaseAwsClientTest {

  public abstract AmazonBedrockClientBuilder configureClient(AmazonBedrockClientBuilder client);

  @Override
  protected boolean hasRequestId() {
    return true;
  }

  @Test
  public void sendRequestWithMockedResponse() throws Exception {
    AmazonBedrockClientBuilder clientBuilder = AmazonBedrockClientBuilder.standard();
    AmazonBedrock client =
        configureClient(clientBuilder)
            .withEndpointConfiguration(endpoint)
            .withCredentials(credentialsProvider)
            .build();

    String body =
        "{"
            + "  \"blockedInputMessaging\": \"string\","
            + "  \"blockedOutputsMessaging\": \"string\","
            + "  \"contentPolicy\": {},"
            + "  \"createdAt\": \"2024-06-12T18:31:45Z\","
            + "  \"description\": \"string\","
            + "  \"guardrailArn\": \"guardrailArn\","
            + "  \"guardrailId\": \"guardrailId\","
            + "  \"kmsKeyArn\": \"string\","
            + "  \"name\": \"string\","
            + "  \"sensitiveInformationPolicy\": {},"
            + "  \"status\": \"READY\","
            + "  \"topicPolicy\": {"
            + "    \"topics\": ["
            + "      {"
            + "        \"definition\": \"string\","
            + "        \"examples\": [ \"string\" ],"
            + "        \"name\": \"string\","
            + "        \"type\": \"string\""
            + "      }"
            + "    ]"
            + "  },"
            + "  \"updatedAt\": \"2024-06-12T18:31:48Z\","
            + "  \"version\": \"DRAFT\","
            + "  \"wordPolicy\": {}"
            + "}";

    server.enqueue(HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, body));

    Object response =
        client.getGuardrail(new GetGuardrailRequest().withGuardrailIdentifier("guardrailId"));

    assertRequestWithMockedResponse(
        response,
        client,
        "Bedrock",
        "GetGuardrail",
        "GET",
        ImmutableMap.of("aws.bedrock.guardrail.id", "guardrailId"));
  }
}
