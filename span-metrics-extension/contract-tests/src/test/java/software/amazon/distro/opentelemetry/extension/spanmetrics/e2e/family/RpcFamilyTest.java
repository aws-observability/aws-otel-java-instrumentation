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

package software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.family;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/** RPC-family attributes from an agent-instrumented gRPC call (echo.Echoer/Echo). */
@Testcontainers(disabledWithoutDocker = true)
class RpcFamilyTest extends FamilyTestBase {

  @Test
  void rpcDerivedAttributesCopied() {
    Map<String, String> attrs = metricAttributesMatching("/grpc", "rpc.system", "grpc");
    assertThat(attrs.get("rpc.service")).isEqualTo("echo.Echoer");
    assertThat(attrs.get("rpc.method")).isEqualTo("Echo");
  }
}
