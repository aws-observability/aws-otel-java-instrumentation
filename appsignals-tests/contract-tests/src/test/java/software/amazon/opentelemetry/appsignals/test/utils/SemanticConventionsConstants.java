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
  public static final String SERVER_ADDRESS = "server.address";
  public static final String SERVER_PORT = "server.port";
  public static final String NETWORK_PROTOCOL_NAME = "network.protocol.name";
  public static final String NETWORK_PROTOCOL_VERSION = "network.protocol.version";
  public static final String NETWORK_LOCAL_ADDRESS = "network.local.address";
  public static final String NETWORK_LOCAL_PORT = "network.local.port";
  public static final String NETWORK_PEER_ADDRESS = "network.peer.address";
  public static final String NETWORK_PEER_PORT = "network.peer.port";
  public static final String NET_SOCK_PEER_NAME = "net.sock.peer.name";
  public static final String HTTP_RESPONSE_STATUS_CODE = "http.response.status_code";
  public static final String URL_SCHEME = "url.scheme";
  public static final String URL_PATH = "url.path";
  public static final String HTTP_RESPONSE_HEADER_CONTENT_LENGTH =
      "http.response.header.content-length";
  public static final String URL_FULL = "url.full";
  public static final String HTTP_REQUEST_METHOD = "http.request.method";
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
  public static final String AWS_GUARDRAIL_ARN = "aws.bedrock.guardrail.arn";
  public static final String GEN_AI_REQUEST_MODEL = "gen_ai.request.model";
  public static final String GEN_AI_REQUEST_MAX_TOKENS = "gen_ai.request.max_tokens";
  public static final String GEN_AI_REQUEST_TEMPERATURE = "gen_ai.request.temperature";
  public static final String GEN_AI_REQUEST_TOP_P = "gen_ai.request.top_p";
  public static final String GEN_AI_RESPONSE_FINISH_REASONS = "gen_ai.response.finish_reasons";
  public static final String GEN_AI_USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens";
  public static final String GEN_AI_USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";
  public static final String AWS_SECRET_ARN = "aws.secretsmanager.secret.arn";
  public static final String AWS_STATE_MACHINE_ARN = "aws.stepfunctions.state_machine.arn";
  public static final String AWS_ACTIVITY_ARN = "aws.stepfunctions.activity.arn";
  public static final String AWS_TOPIC_ARN = "aws.sns.topic.arn";

  // kafka
  public static final String MESSAGING_CLIENT_ID = "messaging.client_id";
  public static final String MESSAGING_DESTINATION_NAME = "messaging.destination.name";
  //  Rename `messaging.kafka.destination.partition` to `messaging.destination.partition.id`
  //
  // https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/57c7cf2ad5f7272c1eb83b9816e652bf832c91d4/CHANGELOG.md?plain=1#L430C3-L430C89
  public static final String MESSAGING_DESTINATION_PARTITION_ID =
      "messaging.destination.partition.id";
  public static final String MESSAGING_KAFKA_MESSAGE_OFFSET = "messaging.kafka.message.offset";
  public static final String MESSAGING_SYSTEM = "messaging.system";
  public static final String MESSAGING_KAFKA_CONSUMER_GROUP = "messaging.kafka.consumer.group";
  public static final String MESSAGING_MESSAGE_BODY_SIZE = "messaging.message.body.size";
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
