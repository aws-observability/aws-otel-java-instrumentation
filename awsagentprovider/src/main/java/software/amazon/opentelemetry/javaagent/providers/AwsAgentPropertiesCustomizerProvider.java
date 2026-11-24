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

package software.amazon.opentelemetry.javaagent.providers;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import java.util.HashMap;

public class AwsAgentPropertiesCustomizerProvider implements AutoConfigurationCustomizerProvider {

  static final String SENSITIVE_QUERY_PARAMETERS_CONFIG =
      "otel.instrumentation.sanitization.url.experimental.sensitive-query-parameters";

  static final String AWS_SENSITIVE_QUERY_PARAMETERS =
      String.join(
          ",",
          "AWSAccessKeyId",
          "Signature",
          "sig",
          "X-Goog-Signature",
          "X-Amz-Signature",
          "X-Amz-Credential",
          "X-Amz-Security-Token");

  @Override
  public void customize(AutoConfigurationCustomizer autoConfiguration) {
    autoConfiguration.addPropertiesSupplier(
        () ->
            new HashMap<String, String>() {
              {
                put("otel.propagators", "baggage,xray,tracecontext");
                put("otel.instrumentation.aws-sdk.experimental-span-attributes", "true");
                put(
                    "otel.instrumentation.aws-sdk.experimental-record-individual-http-error",
                    "true");
                put(SENSITIVE_QUERY_PARAMETERS_CONFIG, AWS_SENSITIVE_QUERY_PARAMETERS);
              }
            });
  }
}
