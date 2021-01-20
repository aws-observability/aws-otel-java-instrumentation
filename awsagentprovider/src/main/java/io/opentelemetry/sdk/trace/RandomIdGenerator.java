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

package io.opentelemetry.sdk.trace;

import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.sdk.extension.aws.trace.AwsXrayIdGenerator;
import java.util.concurrent.ThreadLocalRandom;

// TODO(anuraaga): Remove this hack when upgrading to OTel 0.15.0. There is an issue in 0.14.0 that
// prevents
// using SPI to override the ID generator.
enum RandomIdGenerator implements IdGenerator {
  INSTANCE;

  private static final long INVALID_ID = 0;

  @Override
  public String generateSpanId() {
    // Inline implementation since AwsXrayIdGenerator would delegate back to RandomIdGenerator
    // causing an infinite loop.
    long id;
    ThreadLocalRandom random = ThreadLocalRandom.current();
    do {
      id = random.nextLong();
    } while (id == INVALID_ID);
    return SpanId.fromLong(id);
  }

  @Override
  public String generateTraceId() {
    return AwsXrayIdGenerator.getInstance().generateTraceId();
  }
}
