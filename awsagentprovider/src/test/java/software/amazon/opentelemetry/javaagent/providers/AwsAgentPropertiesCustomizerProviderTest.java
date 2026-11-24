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

import static org.assertj.core.api.Assertions.assertThat;
import static software.amazon.opentelemetry.javaagent.providers.AwsAgentPropertiesCustomizerProvider.AWS_SENSITIVE_QUERY_PARAMETERS;
import static software.amazon.opentelemetry.javaagent.providers.AwsAgentPropertiesCustomizerProvider.SENSITIVE_QUERY_PARAMETERS_CONFIG;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AwsAgentPropertiesCustomizerProviderTest {

  @Test
  void sensitiveQueryParametersConfigKeyIsCorrect() {
    assertThat(SENSITIVE_QUERY_PARAMETERS_CONFIG)
        .isEqualTo("otel.instrumentation.sanitization.url.experimental.sensitive-query-parameters");
  }

  @Test
  void sensitiveQueryParametersIncludesAwsPresignedUrlCredentials() {
    Set<String> params = new HashSet<>(Arrays.asList(AWS_SENSITIVE_QUERY_PARAMETERS.split(",")));

    assertThat(params).contains("X-Amz-Signature");
    assertThat(params).contains("X-Amz-Credential");
    assertThat(params).contains("X-Amz-Security-Token");
  }

  @Test
  void sensitiveQueryParametersIncludesUpstreamDefaults() {
    Set<String> params = new HashSet<>(Arrays.asList(AWS_SENSITIVE_QUERY_PARAMETERS.split(",")));

    assertThat(params).contains("AWSAccessKeyId");
    assertThat(params).contains("Signature");
    assertThat(params).contains("sig");
    assertThat(params).contains("X-Goog-Signature");
  }
}
