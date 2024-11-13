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

package software.amazon.opentelemetry.appsignals.test.utils;

/***
 * Constants for attributes defined in semantic conventions.
 */
public class SemanticConventionsConstants {

  // Semantic Conventions Attribute names
  public static final String NET_PEER_NAME = "net.peer.name";
  public static final String NET_PEER_PORT = "net.peer.port";
  public static final String NET_PROTOCOL_NAME = "net.protocol.name";
  public static final String NET_PROTOCOL_VERSION = "net.protocol.version";
  public static final String NET_HOST_NAME = "net.host.name";
  public static final String NET_HOST_PORT = "net.host.port";
  public static final String NET_SOCK_HOST_ADDR = "net.sock.host.addr";
  public static final String NET_SOCK_HOST_PORT = "net.sock.host.port";
  public static final String NET_SOCK_PEER_ADDR = "net.sock.peer.addr";
  public static final String NET_SOCK_PEER_PORT = "net.sock.peer.port";
  public static final String NET_SOCK_PEER_NAME = "net.sock.peer.name";
  public static final String HTTP_STATUS_CODE = "http.status_code";
  public static final String HTTP_SCHEME = "http.scheme";
  public static final String HTTP_TARGET = "http.target";
  public static final String HTTP_RESPONSE_CONTENT_LENGTH = "http.response_content_length";
  public static final String HTTP_URL = "http.url";
  public static final String HTTP_METHOD = "http.method";
  public static final String HTTP_ROUTE = "http.route";

  public static final String PEER_SERVICE = "peer.service";

  public static final String THREAD_ID = "thread.id";
  public static final String THREAD_NAME = "thread.name";
  public static final String USER_AGENT_ORIGINAL = "user_agent.original";

  public static final String RPC_METHOD = "rpc.method";
  public static final String RPC_SERVICE = "rpc.service";

  public static final String DB_OPERATION = "db.operation";
  public static final String DB_SYSTEM = "db.system";

  // These are not official semantic attributes
  public static final String AWS_BUCKET_NAME = "aws.bucket.name";
  public static final String AWS_TABLE_NAME = "aws.table.name";
  public static final String AWS_QUEUE_URL = "aws.queue.url";
  public static final String AWS_QUEUE_NAME = "aws.queue.name";
  public static final String AWS_STREAM_NAME = "aws.stream.name";
  public static final String AWS_KNOWLEDGE_BASE_ID = "aws.bedrock.knowledge_base.id";
  public static final String AWS_DATA_SOURCE_ID = "aws.bedrock.data_source.id";
  public static final String AWS_AGENT_ID = "aws.bedrock.agent.id";
  public static final String AWS_GUARDRAIL_ID = "aws.bedrock.guardrail.id";
  public static final String GEN_AI_REQUEST_MODEL = "gen_ai.request.model";
  public static final String GEN_AI_REQUEST_MAX_TOKENS = "gen_ai.request.max_tokens";
  public static final String GEN_AI_REQUEST_TEMPERATURE = "gen_ai.request.temperature";
  public static final String GEN_AI_REQUEST_TOP_P = "gen_ai.request.top_p";
  public static final String GEN_AI_RESPONSE_FINISH_REASONS = "gen_ai.response.finish_reasons";
  public static final String GEN_AI_USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens";
  public static final String GEN_AI_USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";

  // kafka
  public static final String MESSAGING_CLIENT_ID = "messaging.client_id";
  public static final String MESSAGING_DESTINATION_NAME = "messaging.destination.name";
  public static final String MESSAGING_KAFKA_DESTINATION_PARTITION =
      "messaging.kafka.destination.partition";
  public static final String MESSAGING_KAFKA_MESSAGE_OFFSET = "messaging.kafka.message.offset";
  public static final String MESSAGING_SYSTEM = "messaging.system";
  public static final String MESSAGING_KAFKA_CONSUMER_GROUP = "messaging.kafka.consumer.group";
  public static final String MESSAGING_MESSAGE_PAYLOAD_SIZE_BYTES =
      "messaging.message.payload_size_bytes";
  public static final String MESSAGING_OPERATION = "messaging.operation";

  // GRPC specific semantic attributes
  public static final String RPC_GRPC_STATUS_CODE = "rpc.grpc.status_code";
  public static final String RPC_SYSTEM = "rpc.system";

  // JDBC
  public static final String DB_CONNECTION_STRING = "db.connection_string";
  public static final String DB_NAME = "db.name";
  public static final String DB_SQL_TABLE = "db.sql.table";
  public static final String DB_STATEMENT = "db.statement";
  public static final String DB_USER = "db.user";
}
