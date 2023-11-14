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

package com.amazon.aoc.enums;

import lombok.Getter;

@Getter
public enum GenericConstants {
  // retry
  SLEEP_IN_MILLISECONDS("10000"), // ms
  SLEEP_IN_SECONDS("30"),
  MAX_RETRIES("10"),

  // validator env vars
  ENV_VAR_AGENT_VERSION("AGENT_VERSION"),
  ENV_VAR_TESTING_ID("TESTING_ID"),
  ENV_VAR_EXPECTED_METRIC("EXPECTED_METRIC"),
  ENV_VAR_EXPECTED_TRACE("EXPECTED_TRACE"),
  ENV_VAR_REGION("REGION"),
  ENV_VAR_NAMESPACE("NAMESPACE"),
  ENV_VAR_DATA_EMITTER_ENDPOINT("DATA_EMITTER_ENDPOINT"),

  // XRay sdk related
  HTTP_HEADER_TRACE_ID("X-Amzn-Trace-Id"),
  ;

  private String val;

  GenericConstants(String val) {
    this.val = val;
  }
}
