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

import com.amazonaws.services.bedrockagent.AWSBedrockAgent;
import com.amazonaws.services.bedrockagent.AWSBedrockAgentClientBuilder;
import com.amazonaws.services.bedrockagent.model.GetAgentRequest;
import com.amazonaws.services.bedrockagent.model.GetDataSourceRequest;
import com.amazonaws.services.bedrockagent.model.GetKnowledgeBaseRequest;
import com.google.common.collect.ImmutableMap;
import io.opentelemetry.testing.internal.armeria.common.HttpResponse;
import io.opentelemetry.testing.internal.armeria.common.HttpStatus;
import io.opentelemetry.testing.internal.armeria.common.MediaType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/*
 * This is a new class created during ADOT git patching.
 */
public abstract class AbstractBedrockAgentClientTest extends AbstractBaseAwsClientTest {

  public abstract AWSBedrockAgentClientBuilder configureClient(AWSBedrockAgentClientBuilder client);

  @Override
  protected boolean hasRequestId() {
    return true;
  }

  @Test
  public void sendGetAgentRequest() throws Exception {
    AWSBedrockAgent client = createClient();

    server.enqueue(HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "{}"));

    Object response = client.getAgent(new GetAgentRequest().withAgentId("agentId"));

    assertRequestWithMockedResponse(
        response,
        client,
        "AWSBedrockAgent",
        "GetAgent",
        "GET",
        ImmutableMap.of("aws.bedrock.agent.id", "agentId"));
  }

  @Test
  public void sendGetKnowledgeBaseRequest() throws Exception {
    AWSBedrockAgent client = createClient();

    server.enqueue(HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "{}"));

    Object response =
        client.getKnowledgeBase(
            new GetKnowledgeBaseRequest().withKnowledgeBaseId("knowledgeBaseId"));

    assertRequestWithMockedResponse(
        response,
        client,
        "AWSBedrockAgent",
        "GetKnowledgeBase",
        "GET",
        ImmutableMap.of("aws.bedrock.knowledge_base.id", "knowledgeBaseId"));
  }

  @Test
  public void sendGetDataSourceRequest() throws Exception {
    AWSBedrockAgent client = createClient();

    server.enqueue(HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "{}"));

    Object response =
        client.getDataSource(
            new GetDataSourceRequest()
                .withDataSourceId("datasourceId")
                .withKnowledgeBaseId("knowledgeBaseId"));

    Map<String, String> additionalAttributes =
        ImmutableMap.of("aws.bedrock.data_source.id", "datasourceId");

    assertRequestWithMockedResponse(
        response, client, "AWSBedrockAgent", "GetDataSource", "GET", additionalAttributes);
  }

  private AWSBedrockAgent createClient() {
    AWSBedrockAgentClientBuilder clientBuilder = AWSBedrockAgentClientBuilder.standard();
    return configureClient(clientBuilder)
        .withEndpointConfiguration(endpoint)
        .withCredentials(credentialsProvider)
        .build();
  }
}
