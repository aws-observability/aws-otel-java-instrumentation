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

import com.amazonaws.services.lambda.AWSLambda;
import com.amazonaws.services.lambda.AWSLambdaClientBuilder;
import com.amazonaws.services.lambda.model.GetEventSourceMappingRequest;
import com.amazonaws.services.lambda.model.GetFunctionRequest;
import com.google.common.collect.ImmutableMap;
import io.opentelemetry.testing.internal.armeria.common.HttpResponse;
import io.opentelemetry.testing.internal.armeria.common.HttpStatus;
import io.opentelemetry.testing.internal.armeria.common.MediaType;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/*
 * This is a new class created during ADOT git patching.
 */
public abstract class AbstractLambdaClientTest extends AbstractBaseAwsClientTest {

  public abstract AWSLambdaClientBuilder configureClient(AWSLambdaClientBuilder client);

  @Override
  protected boolean hasRequestId() {
    return false;
  }

  @ParameterizedTest
  @MethodSource("provideArguments")
  public void testSendRequestWithMockedResponse(
      String operation, Map<String, String> additionalAttributes, Function<AWSLambda, Object> call)
      throws Exception {

    AWSLambdaClientBuilder clientBuilder = AWSLambdaClientBuilder.standard();

    AWSLambda client =
        configureClient(clientBuilder)
            .withEndpointConfiguration(endpoint)
            .withCredentials(credentialsProvider)
            .build();

    server.enqueue(HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, ""));

    Object response = call.apply(client);
    assertRequestWithMockedResponse(
        response, client, "AWSLambda", operation, "GET", additionalAttributes);
  }

  private static Stream<Arguments> provideArguments() {
    return Stream.of(
        Arguments.of(
            "GetEventSourceMapping",
            ImmutableMap.of("aws.lambda.resource_mapping.id", "uuid"),
            (Function<AWSLambda, Object>)
                c -> c.getEventSourceMapping(new GetEventSourceMappingRequest().withUUID("uuid"))),
        Arguments.of(
            "GetFunction",
            ImmutableMap.of("aws.lambda.function.name", "functionName"),
            (Function<AWSLambda, Object>)
                c -> c.getFunction(new GetFunctionRequest().withFunctionName("functionName"))));
  }
}
