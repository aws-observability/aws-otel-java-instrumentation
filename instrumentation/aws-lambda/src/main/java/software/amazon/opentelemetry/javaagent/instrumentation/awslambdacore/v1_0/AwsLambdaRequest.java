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

package software.amazon.opentelemetry.javaagent.instrumentation.awslambdacore.v1_0;

import com.amazonaws.services.lambda.runtime.Context;
import com.google.auto.value.AutoValue;
import java.util.Map;

@AutoValue
public abstract class AwsLambdaRequest {

  public static AwsLambdaRequest create(
      Context awsContext, Object input, Map<String, String> headers) {
    return new AutoValue_AwsLambdaRequest(awsContext, input, headers);
  }

  public abstract Context getAwsContext();

  public abstract Object getInput();

  public abstract Map<String, String> getHeaders();
}
