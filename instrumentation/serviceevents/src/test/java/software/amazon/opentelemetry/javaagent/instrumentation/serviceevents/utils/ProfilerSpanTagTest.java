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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.ProfilerSpanTag.Decoded;

/**
 * Round-trip tests for the {@code profiler.Span} tag encoding. The span processor {@link
 * ProfilerSpanTag#encode encode}s an operation plus (only when sampled) trace/span ids into the
 * tag; the scanner {@link ProfilerSpanTag#decode decode}s it back out of the {@code profiler.Span}
 * event.
 */
class ProfilerSpanTagTest {

  private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c"; // 32 hex
  private static final String SPAN_ID = "b7ad6b7169203331"; // 16 hex

  @Test
  void sampled_roundTripsOperationAndTrace() {
    String tag = ProfilerSpanTag.encode("GET /api/orders", TRACE_ID, SPAN_ID);
    Decoded d = ProfilerSpanTag.decode(tag);
    assertEquals("GET /api/orders", d.operation);
    assertEquals(TRACE_ID, d.traceId);
    assertEquals(SPAN_ID, d.spanId);
  }

  @Test
  void unsampled_roundTripsOperationOnly_noLink() {
    String tag = ProfilerSpanTag.encode("POST /api/checkout", null, null);
    Decoded d = ProfilerSpanTag.decode(tag);
    assertEquals("POST /api/checkout", d.operation);
    assertNull(d.traceId, "unsampled tag must decode to no traceId (operation only)");
    assertNull(d.spanId, "unsampled tag must decode to no spanId");
  }

  @Test
  void operationContainingPipe_isReversible() {
    // The two trailing separators + fixed-width hex fields make decode robust even if the operation
    // itself contains '|': decode splits from the right on the last two separators.
    String op = "GET /a|b/c";
    String tag = ProfilerSpanTag.encode(op, TRACE_ID, SPAN_ID);
    Decoded d = ProfilerSpanTag.decode(tag);
    assertEquals(op, d.operation);
    assertEquals(TRACE_ID, d.traceId);
    assertEquals(SPAN_ID, d.spanId);
  }

  @Test
  void malformedHex_yieldsOperationOnly() {
    // Wrong-length / non-hex trace fields must not surface as a Link.
    Decoded shortIds = ProfilerSpanTag.decode(ProfilerSpanTag.encode("GET /x", "abc", "def"));
    assertEquals("GET /x", shortIds.operation);
    assertNull(shortIds.traceId);
    assertNull(shortIds.spanId);

    Decoded nonHex =
        ProfilerSpanTag.decode(
            ProfilerSpanTag.encode("GET /x", "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz", SPAN_ID));
    assertEquals("GET /x", nonHex.operation);
    assertNull(nonHex.traceId, "non-hex traceId must be rejected");
    assertNull(
        nonHex.spanId, "a Link needs both ids — invalid traceId drops the spanId too (no Link)");
  }

  @Test
  void nullOrEmptyTag_decodesToNull() {
    assertNull(ProfilerSpanTag.decode(null));
    assertNull(ProfilerSpanTag.decode(""));
  }

  @Test
  void plainOperation_noSeparators_treatedAsOperation() {
    // Defensive: a tag with no separators is treated as a bare operation.
    Decoded d = ProfilerSpanTag.decode("GET /health");
    assertEquals("GET /health", d.operation);
    assertNull(d.traceId);
    assertNull(d.spanId);
  }
}
