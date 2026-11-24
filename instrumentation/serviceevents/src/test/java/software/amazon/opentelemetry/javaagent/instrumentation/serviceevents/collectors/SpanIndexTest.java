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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.SpanMetadata;

/**
 * {@link SpanIndex} must attribute every sample exactly like the per-thread {@code TreeMap} index
 * it replaced: equal starts collapse to the latest end (later-added on a tie), and a lookup walks
 * back from the latest start at or before the sample, at most {@code maxWalk} spans, returning the
 * first that strictly contains it.
 */
class SpanIndexTest {

  /** The previous implementation, kept here as the reference. */
  private static SpanMetadata referenceFind(
      TreeMap<Long, SpanMetadata> spans, long t, int maxWalk) {
    Map.Entry<Long, SpanMetadata> e = spans.floorEntry(t);
    for (int walk = 0; e != null && walk < maxWalk; walk++) {
      SpanMetadata s = e.getValue();
      if (t > s.startNs && t < s.endNs) {
        return s;
      }
      e = spans.lowerEntry(e.getKey());
    }
    return null;
  }

  private static String hex(Random r, int len) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < len; i++) {
      b.append(Character.forDigit(r.nextInt(16), 16));
    }
    return b.toString();
  }

  @Test
  void find_matchesTreeMapReference_onRandomOverlappingSpans() {
    Random r = new Random(42);
    for (int round = 0; round < 50; round++) {
      SpanIndex index = new SpanIndex();
      TreeMap<Long, SpanMetadata> reference = new TreeMap<>();
      int n = 1 + r.nextInt(400);
      for (int i = 0; i < n; i++) {
        // Small time range so starts collide and spans nest and overlap.
        long start = r.nextInt(500);
        long end = start + r.nextInt(60);
        String op = "GET /r" + r.nextInt(5);
        boolean linked = r.nextBoolean();
        String trace = linked ? hex(r, 32) : null;
        String span = linked ? hex(r, 16) : null;
        index.add("t", start, end, op, trace, span);
        reference.merge(
            start,
            new SpanMetadata(op, start, end, trace, span),
            (existing, added) -> added.endNs >= existing.endNs ? added : existing);
      }
      index.seal();
      assertEquals(reference.size(), index.size());
      for (long t = -1; t <= 562; t++) {
        for (int maxWalk : new int[] {1, 3, 16}) {
          SpanMetadata want = referenceFind(reference, t, maxWalk);
          SpanIndex.Match got = index.find("t", t, maxWalk);
          if (want == null) {
            assertNull(got, "t=" + t);
          } else {
            assertEquals(want.operation, got.operation, "t=" + t);
            assertEquals(want.traceId, got.traceId, "t=" + t);
            assertEquals(want.spanId, got.spanId, "t=" + t);
          }
        }
      }
    }
  }

  @Test
  void find_isPerThread_andBoundariesAreExclusive() {
    SpanIndex index = new SpanIndex();
    index.add("a", 10, 20, "GET /a", null, null);
    index.add("b", 10, 20, "GET /b", null, null);
    index.seal();
    assertEquals("GET /a", index.find("a", 15, 16).operation);
    assertEquals("GET /b", index.find("b", 15, 16).operation);
    assertNull(index.find("a", 10, 16), "start is exclusive");
    assertNull(index.find("a", 20, 16), "end is exclusive");
    assertNull(index.find("c", 15, 16), "unknown thread");
  }

  @Test
  void ids_roundTripIncludingLeadingZeros_andNonHexIdsAreKeptVerbatim() {
    SpanIndex index = new SpanIndex();
    index.add("t", 0, 10, "op", "00000000000000000000000000000001", "0000000000000002");
    index.add("t", 20, 30, "op", "not-a-trace-id", "x");
    index.seal();
    SpanIndex.Match a = index.find("t", 5, 16);
    assertEquals("00000000000000000000000000000001", a.traceId);
    assertEquals("0000000000000002", a.spanId);
    SpanIndex.Match b = index.find("t", 25, 16);
    assertEquals("not-a-trace-id", b.traceId);
    assertEquals("x", b.spanId);
  }
}
