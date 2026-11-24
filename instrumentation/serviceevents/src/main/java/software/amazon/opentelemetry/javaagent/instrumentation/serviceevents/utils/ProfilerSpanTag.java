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

/**
 * Compact, reversible encoding of the correlation payload carried by an async-profiler {@code
 * profiler.Span} event tag.
 *
 * <p>The span processor calls {@code one.profiler.Span.end(token, tag)} with a tag produced by
 * {@link #encode}; the profiler scanner recovers the fields with {@link #decode} when it reads the
 * {@code profiler.Span} events back out of the JFR.
 *
 * <p>Format: {@code operation + '|' + traceIdHex + '|' + spanIdHex}. The two {@code '|'} separators
 * are always present; the trace fields are empty for unsampled requests (operation only, no {@code
 * Link}). Because {@code traceId}/{@code spanId} are hex they never contain {@code '|'} and the
 * variable-length {@code operation} is first, decoding splits from the right on the last two
 * separators — reversible even if the operation itself contains {@code '|'}.
 */
public final class ProfilerSpanTag {

  private static final char SEP = '|';
  private static final int TRACE_ID_HEX_LEN = 32;
  private static final int SPAN_ID_HEX_LEN = 16;

  private ProfilerSpanTag() {}

  /**
   * Encode an operation plus optional trace context into a {@code profiler.Span} tag.
   *
   * @param operation the App Signals operation (e.g. {@code "GET /api/orders"}); {@code null}
   *     becomes empty
   * @param traceId 32-char hex trace id, or {@code null} when the trace was not sampled
   * @param spanId 16-char hex span id, or {@code null} when the trace was not sampled
   */
  public static String encode(String operation, String traceId, String spanId) {
    String op = operation == null ? "" : operation;
    String t = traceId == null ? "" : traceId;
    String s = spanId == null ? "" : spanId;
    return op + SEP + t + SEP + s;
  }

  /**
   * Decode a {@code profiler.Span} tag back into operation / traceId / spanId. Returns {@code null}
   * for a {@code null}/empty tag. Trace fields are only surfaced when they are well-formed hex of
   * the expected length (32 for traceId, 16 for spanId), so a malformed or unsampled tag yields a
   * decode with a {@code null} traceId/spanId (operation only, no {@code Link}).
   */
  public static Decoded decode(String tag) {
    if (tag == null || tag.isEmpty()) {
      return null;
    }
    int i2 = tag.lastIndexOf(SEP);
    int i1 = i2 > 0 ? tag.lastIndexOf(SEP, i2 - 1) : -1;
    if (i1 < 0) {
      // No trace separators — treat the whole tag as the operation (defensive).
      return new Decoded(tag, null, null);
    }
    String operation = tag.substring(0, i1);
    String traceId = validHexOrNull(tag.substring(i1 + 1, i2), TRACE_ID_HEX_LEN);
    String spanId = validHexOrNull(tag.substring(i2 + 1), SPAN_ID_HEX_LEN);
    // A trace Link needs both ids; encode only ever writes both-or-neither, so surface the trace
    // context only when both are well-formed (otherwise operation only, no Link).
    if (traceId == null || spanId == null) {
      traceId = null;
      spanId = null;
    }
    return new Decoded(operation.isEmpty() ? null : operation, traceId, spanId);
  }

  private static String validHexOrNull(String value, int expectedLen) {
    if (value == null || value.length() != expectedLen) {
      return null;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
      if (!hex) {
        return null;
      }
    }
    return value;
  }

  /** Decoded correlation fields from a {@code profiler.Span} tag. */
  public static final class Decoded {
    public final String operation;
    public final String traceId;
    public final String spanId;

    public Decoded(String operation, String traceId, String spanId) {
      this.operation = operation;
      this.traceId = traceId;
      this.spanId = spanId;
    }
  }
}
