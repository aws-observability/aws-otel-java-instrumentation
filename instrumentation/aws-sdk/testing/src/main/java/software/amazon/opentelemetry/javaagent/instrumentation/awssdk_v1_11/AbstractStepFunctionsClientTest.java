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

import com.amazonaws.services.stepfunctions.AWSStepFunctions;
import com.amazonaws.services.stepfunctions.AWSStepFunctionsClientBuilder;
import com.amazonaws.services.stepfunctions.model.DescribeActivityRequest;
import com.amazonaws.services.stepfunctions.model.DescribeStateMachineRequest;
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
public abstract class AbstractStepFunctionsClientTest extends AbstractBaseAwsClientTest {

  public abstract AWSStepFunctionsClientBuilder configureClient(
      AWSStepFunctionsClientBuilder client);

  @Override
  protected boolean hasRequestId() {
    return false;
  }

  @ParameterizedTest
  @MethodSource("provideArguments")
  public void testSendRequestWithMockedResponse(
      String operation,
      Map<String, String> additionalAttributes,
      Function<AWSStepFunctions, Object> call)
      throws Exception {

    AWSStepFunctionsClientBuilder clientBuilder = AWSStepFunctionsClientBuilder.standard();

    AWSStepFunctions client =
        configureClient(clientBuilder)
            .withEndpointConfiguration(endpoint)
            .withCredentials(credentialsProvider)
            .build();

    server.enqueue(HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, ""));

    Object response = call.apply(client);
    assertRequestWithMockedResponse(
        response, client, "AWSStepFunctions", operation, "POST", additionalAttributes);
  }

  private static Stream<Arguments> provideArguments() {
    return Stream.of(
        Arguments.of(
            "DescribeStateMachine",
            ImmutableMap.of("aws.stepfunctions.state_machine.arn", "stateMachineArn"),
            (Function<AWSStepFunctions, Object>)
                c ->
                    c.describeStateMachine(
                        new DescribeStateMachineRequest().withStateMachineArn("stateMachineArn"))),
        Arguments.of(
            "DescribeActivity",
            ImmutableMap.of("aws.stepfunctions.activity.arn", "activityArn"),
            (Function<AWSStepFunctions, Object>)
                c ->
                    c.describeActivity(
                        new DescribeActivityRequest().withActivityArn("activityArn"))));
  }
}
