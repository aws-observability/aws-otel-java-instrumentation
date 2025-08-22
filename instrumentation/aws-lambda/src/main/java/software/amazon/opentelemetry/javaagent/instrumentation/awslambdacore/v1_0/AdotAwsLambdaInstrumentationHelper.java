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

import io.opentelemetry.api.GlobalOpenTelemetry;

public class AdotAwsLambdaInstrumentationHelper {
  private static final AwsLambdaFunctionInstrumenter FUNCTION_INSTRUMENTER =
      AwsLambdaFunctionInstrumenterFactory.createInstrumenter(GlobalOpenTelemetry.get());

  public static AwsLambdaFunctionInstrumenter functionInstrumenter() {
    return FUNCTION_INSTRUMENTER;
  }

  private AdotAwsLambdaInstrumentationHelper() {}
}
