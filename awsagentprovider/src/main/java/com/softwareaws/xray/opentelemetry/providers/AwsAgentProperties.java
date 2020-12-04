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

package com.softwareaws.xray.opentelemetry.providers;

import io.opentelemetry.javaagent.spi.config.PropertySource;
import java.util.Collections;
import java.util.Map;

public class AwsAgentProperties implements PropertySource {
  @Override
  public Map<String, String> getProperties() {
    return Collections.singletonMap("otel.propagators", "xray,tracecontext,b3,b3multi");
  }
}
