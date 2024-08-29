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

package com.amazon.sampleapp;

import static spark.Spark.get;
import static spark.Spark.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class Utils {
  private static ObjectMapper objectMapper = new ObjectMapper();

  // Response with BedrockAgent GetKnowledgeBaseRequest.
  public static void setupGetKnowledgeBaseRoute(int status) {
    get(
        "/knowledgebases/:knowledgeBaseId",
        (req, res) -> {
          String knowledgeBaseId = req.params(":knowledgeBaseId");
          String createdAt =
              Instant.ofEpochSecond(1720600797L)
                  .atOffset(ZoneOffset.UTC)
                  .format(DateTimeFormatter.ISO_INSTANT);
          String updatedAt =
              Instant.ofEpochSecond(1720773597L)
                  .atOffset(ZoneOffset.UTC)
                  .format(DateTimeFormatter.ISO_INSTANT);
          ObjectNode knowledgeBase = objectMapper.createObjectNode();
          knowledgeBase.put("createdAt", createdAt);
          knowledgeBase.put(
              "knowledgeBaseArn",
              "arn:aws:bedrock:us-west-2:000000000000:knowledge-base/" + knowledgeBaseId);
          ObjectNode knowledgeBaseConfiguration = objectMapper.createObjectNode();
          knowledgeBaseConfiguration.put("type", "VECTOR");
          knowledgeBase.set("knowledgeBaseConfiguration", knowledgeBaseConfiguration);
          knowledgeBase.put("knowledgeBaseId", knowledgeBaseId);
          knowledgeBase.put("name", "test-knowledge-base");
          knowledgeBase.put("roleArn", "arn:aws:iam::000000000000:role/TestKnowledgeBase");
          knowledgeBase.put("status", "ACTIVE");
          ObjectNode storageConfiguration = objectMapper.createObjectNode();
          storageConfiguration.put("type", "OPENSEARCH_SERVERLESS");
          knowledgeBase.set("storageConfiguration", storageConfiguration);
          knowledgeBase.put("updatedAt", updatedAt);
          ObjectNode responseBody = objectMapper.createObjectNode();
          responseBody.set("knowledgeBase", knowledgeBase);
          String jsonResponse = objectMapper.writeValueAsString(responseBody);

          res.status(status);
          res.type("application/json");
          return jsonResponse;
        });
  }

  // Response with BedrockAgent GetAgentRequest.
  public static void setupGetAgentRoute(int status) {
    get(
        "/agents/:agentId/",
        (req, res) -> {
          String agentId = req.params(":agentId");
          ObjectNode agentNode = objectMapper.createObjectNode();
          agentNode.put("agentArn", "arn:aws:bedrock:us-east-1:000000000000:agent/" + agentId);
          agentNode.put("agentId", agentId);
          agentNode.put("agentName", "test-bedrock-agent");
          agentNode.put("agentResourceRoleArn", "arn:aws:iam::000000000000:role/TestAgent");
          agentNode.put("agentStatus", "PREPARED");
          agentNode.put("agentVersion", "DRAFT");
          agentNode.put("createdAt", "2024-07-17T12:00:00Z");
          agentNode.put("idleSessionTTLInSeconds", 60);
          agentNode.put("updatedAt", "2024-07-17T12:30:00Z");
          ObjectNode responseBody = objectMapper.createObjectNode();
          responseBody.set("agent", agentNode);
          String jsonResponse = responseBody.toString();

          res.status(status);
          res.type("application/json");
          return jsonResponse;
        });
  }

  // Response with BedrockAgent GetDataSourceRequest.
  public static void setupGetDataSourceRoute(int status) {
    get(
        "/knowledgebases/:knowledgeBaseId/datasources/:dataSourceId",
        (req, res) -> {
          String dataSourceId = req.params(":dataSourceId");
          String knowledgeBaseId = req.params(":knowledgeBaseId");
          ObjectNode dataSourceNode = objectMapper.createObjectNode();
          dataSourceNode.put("createdAt", "2024-07-17T12:00:00Z"); // Example value
          dataSourceNode.put("dataSourceId", dataSourceId);
          dataSourceNode.put("knowledgeBaseId", knowledgeBaseId);
          dataSourceNode.put("name", "example-data-source-name");
          dataSourceNode.put("status", "ACTIVE");
          dataSourceNode.put("updatedAt", "2024-07-17T12:30:00Z"); // Example value
          ObjectNode dataSourceConfigurationNode = objectMapper.createObjectNode();
          dataSourceConfigurationNode.put("type", "EXAMPLE_TYPE");
          dataSourceNode.set("dataSourceConfiguration", dataSourceConfigurationNode);
          ObjectNode responseBody = objectMapper.createObjectNode();
          responseBody.set("dataSource", dataSourceNode);
          String jsonResponse = responseBody.toString();

          res.status(status);
          res.type("application/json");
          return jsonResponse;
        });
  }

  // Response with Bedrock GetGuardrailRequest.
  public static void setupGetGuardrailRoute(int status) {
    get(
        "/guardrails/:guardrailIdentifier",
        (req, res) -> {
          String guardrailId = "test-bedrock-guardrail";
          ObjectNode jsonResponse = objectMapper.createObjectNode();
          jsonResponse.put(
              "guardrailArn", "arn:aws:bedrock:us-east-1:000000000000:guardrail/" + guardrailId);
          jsonResponse.put("guardrailId", guardrailId);
          jsonResponse.put("version", "DRAFT");
          jsonResponse.put("createdAt", "2024-07-17T12:00:00Z");
          jsonResponse.put("updatedAt", "2024-07-17T12:30:00Z");
          jsonResponse.put("blockedInputMessaging", "InputBlocked");
          jsonResponse.put("blockedOutputsMessaging", "OutputBlocked");
          jsonResponse.put("name", "test-guardrail");
          jsonResponse.put("status", "READY");

          res.status(status);
          res.type("application/json");
          return jsonResponse;
        });
  }

  // Response with bedrockAgentRuntime GetAgentMemoryRequest.
  public static void setupGetAgentMemoryRoute(int status) {
    get(
        "/agents/:agentId/agentAliases/:agentAliasId/memories",
        (req, res) -> {
          ObjectNode jsonResponse = objectMapper.createObjectNode();
          jsonResponse.put("nextToken", "testToken");

          ArrayNode memoryContents = objectMapper.createArrayNode();
          jsonResponse.set("memoryContents", memoryContents);

          res.status(status);
          res.type("application/json");
          return jsonResponse;
        });
  }

  // Response with bedrockAgentRuntime RetrieveRequest.
  public static void setupRetrieveRoute(int status) {
    post(
        "/knowledgebases/:knowledgeBaseId/retrieve",
        (req, res) -> {
          ObjectNode jsonResponse = objectMapper.createObjectNode();
          jsonResponse.put("nextToken", "TestNextToken");
          ArrayNode retrievalResults = objectMapper.createArrayNode();
          ObjectNode content = objectMapper.createObjectNode();
          content.put("text", "An example of text content.");
          ObjectNode contentWrapper = objectMapper.createObjectNode();
          contentWrapper.set("content", content);
          retrievalResults.add(contentWrapper);
          jsonResponse.set("retrievalResults", retrievalResults);

          res.status(status);
          res.type("application/json");
          return jsonResponse;
        });
  }

  // Response with bedrockRuntime InvokeModelRequest.
  public static void setupInvokeModelRoute(int status) {
    post(
        "/model/:modelId/invoke",
        (req, res) -> {
          ObjectNode jsonResponse = objectMapper.createObjectNode();
          jsonResponse.put("completion", "A simple completion token.");
          jsonResponse.put("stop_reason", "stop_sequence");
          jsonResponse.put("stop", "stop_token");

          res.status(status);
          res.type("application/json");
          return jsonResponse;
        });
  }
}
