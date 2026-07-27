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

package software.amazon.distro.opentelemetry.extension.spanmetrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

class SpanMetricsCustomizerProviderTest {

  @Test
  void providerIsDiscoverableViaServiceLoader() {
    boolean found = false;
    for (AutoConfigurationCustomizerProvider provider :
        ServiceLoader.load(AutoConfigurationCustomizerProvider.class)) {
      if (provider.getClass().getName().endsWith("SpanMetricsCustomizerProvider")) {
        found = true;
      }
    }
    assertThat(found).isTrue();
  }
}
