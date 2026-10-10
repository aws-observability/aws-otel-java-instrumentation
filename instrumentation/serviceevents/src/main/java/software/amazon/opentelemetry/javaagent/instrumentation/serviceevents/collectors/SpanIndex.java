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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-thread index of one JFR window's request spans, used to attribute each sample to the request
 * it was taken in. Spans are stored in flat primitive arrays (about 45 bytes per span) rather than
 * one object graph per span, because a busy window holds hundreds of thousands of them.
 *
 * <p>Usage: {@link #add} every span, then {@link #seal} once, then {@link #find} per sample. Lookup
 * semantics match a per-thread {@code TreeMap<startNs, span>}: spans with the same start on the
 * same thread collapse to the one with the latest end (the later-added one on a tie), and {@link
 * #find} walks back from the latest span starting at or before the sample time, returning the first
 * that strictly contains it.
 */
final class SpanIndex {

  /** Operation, trace id and span id of a matched span; ids are null when it has no trace link. */
  static final class Match {
    final String operation;
    final String traceId;
    final String spanId;

    Match(String operation, String traceId, String spanId) {
      this.operation = operation;
      this.traceId = traceId;
      this.spanId = spanId;
    }
  }

  /** Receives the spans of one thread in start order; see {@link #forEach}. */
  interface SpanVisitor {
    void visit(long startNs, long endNs, String operation, String traceId, String spanId);
  }

  private final Map<String, ThreadSpans> byThread = new HashMap<>();
  // Distinct operation names, stored once and referenced by index.
  private final List<String> operations = new ArrayList<>();
  private final Map<String, Integer> operationIds = new HashMap<>();

  /**
   * Add a span. {@code traceId} (32 hex chars) and {@code spanId} (16 hex chars) are normally both
   * well-formed or both null, as produced by {@code ProfilerSpanTag.decode}; ids in any other form
   * are kept as given.
   */
  void add(
      String thread, long startNs, long endNs, String operation, String traceId, String spanId) {
    Integer op = operationIds.get(operation);
    if (op == null) {
      op = operations.size();
      operations.add(operation);
      operationIds.put(operation, op);
    }
    ThreadSpans spans = byThread.get(thread);
    if (spans == null) {
      spans = new ThreadSpans();
      byThread.put(thread, spans);
    }
    spans.add(startNs, endNs, op, traceId, spanId);
  }

  /** Sort each thread's spans by start and collapse equal starts; call once, after all adds. */
  void seal() {
    for (ThreadSpans spans : byThread.values()) {
      spans.seal();
    }
  }

  /** Number of spans (after {@link #seal}, equal starts on one thread count once). */
  int size() {
    int n = 0;
    for (ThreadSpans spans : byThread.values()) {
      n += spans.size;
    }
    return n;
  }

  /**
   * The span on {@code thread} that strictly contains {@code timeNs} ({@code start < t < end}),
   * checking at most {@code maxWalk} spans back from the latest one starting at or before {@code
   * timeNs}; null if none. Only valid after {@link #seal}.
   */
  Match find(String thread, long timeNs, int maxWalk) {
    ThreadSpans spans = byThread.get(thread);
    if (spans == null) {
      return null;
    }
    int i = spans.floorIndex(timeNs);
    for (int walk = 0; i >= 0 && walk < maxWalk; walk++, i--) {
      if (timeNs > spans.start[i] && timeNs < spans.end[i]) {
        return spans.match(i, operations);
      }
    }
    return null;
  }

  /** Thread names with at least one span. */
  Iterable<String> threads() {
    return byThread.keySet();
  }

  /** Visit every span of {@code thread} in start order. */
  void forEach(String thread, SpanVisitor visitor) {
    ThreadSpans spans = byThread.get(thread);
    if (spans == null) {
      return;
    }
    for (int i = 0; i < spans.size; i++) {
      Match m = spans.match(i, operations);
      visitor.visit(spans.start[i], spans.end[i], m.operation, m.traceId, m.spanId);
    }
  }

  private static final class ThreadSpans {
    long[] start = new long[16];
    long[] end = new long[16];
    int[] operation = new int[16];
    // Trace id as two longs and span id as one; hasLink is false when the span has no trace.
    long[] traceHi = new long[16];
    long[] traceLo = new long[16];
    long[] spanId = new long[16];
    boolean[] hasLink = new boolean[16];
    // Ids that are not 32/16 hex chars, kept verbatim; allocated only if such a span is added.
    String[][] rawIds;
    int size;

    void add(long s, long e, int op, String traceId, String spanIdHex) {
      if (size == start.length) {
        grow();
      }
      start[size] = s;
      end[size] = e;
      operation[size] = op;
      if (isHex(traceId, 32) && isHex(spanIdHex, 16)) {
        traceHi[size] = Long.parseUnsignedLong(traceId.substring(0, 16), 16);
        traceLo[size] = Long.parseUnsignedLong(traceId.substring(16), 16);
        spanId[size] = Long.parseUnsignedLong(spanIdHex, 16);
        hasLink[size] = true;
      } else if (traceId != null || spanIdHex != null) {
        if (rawIds == null) {
          rawIds = new String[start.length][];
        }
        rawIds[size] = new String[] {traceId, spanIdHex};
      }
      size++;
    }

    private void grow() {
      int n = start.length * 2;
      start = Arrays.copyOf(start, n);
      end = Arrays.copyOf(end, n);
      operation = Arrays.copyOf(operation, n);
      traceHi = Arrays.copyOf(traceHi, n);
      traceLo = Arrays.copyOf(traceLo, n);
      spanId = Arrays.copyOf(spanId, n);
      hasLink = Arrays.copyOf(hasLink, n);
      if (rawIds != null) {
        rawIds = Arrays.copyOf(rawIds, n);
      }
    }

    private static boolean isHex(String s, int len) {
      if (s == null || s.length() != len) {
        return false;
      }
      for (int i = 0; i < len; i++) {
        if (Character.digit(s.charAt(i), 16) < 0) {
          return false;
        }
      }
      return true;
    }

    /**
     * Sort by start (stable, so equal starts keep add order) and keep, for each start, the span
     * with the latest end, the later-added one on a tie. Trims the arrays to size.
     */
    void seal() {
      int[] order = new int[size];
      for (int i = 0; i < size; i++) {
        order[i] = i;
      }
      sortByStart(order, new int[size], 0, size);

      long[] s2 = new long[size];
      long[] e2 = new long[size];
      int[] op2 = new int[size];
      long[] hi2 = new long[size];
      long[] lo2 = new long[size];
      long[] sp2 = new long[size];
      boolean[] link2 = new boolean[size];
      String[][] raw2 = rawIds == null ? null : new String[size][];
      int n = 0;
      for (int k = 0; k < size; ) {
        int best = order[k];
        int j = k + 1;
        while (j < size && start[order[j]] == start[best]) {
          if (end[order[j]] >= end[best]) {
            best = order[j];
          }
          j++;
        }
        s2[n] = start[best];
        e2[n] = end[best];
        op2[n] = operation[best];
        hi2[n] = traceHi[best];
        lo2[n] = traceLo[best];
        sp2[n] = spanId[best];
        link2[n] = hasLink[best];
        if (raw2 != null) {
          raw2[n] = rawIds[best];
        }
        n++;
        k = j;
      }
      start = Arrays.copyOf(s2, n);
      end = Arrays.copyOf(e2, n);
      operation = Arrays.copyOf(op2, n);
      traceHi = Arrays.copyOf(hi2, n);
      traceLo = Arrays.copyOf(lo2, n);
      spanId = Arrays.copyOf(sp2, n);
      hasLink = Arrays.copyOf(link2, n);
      rawIds = raw2 == null ? null : Arrays.copyOf(raw2, n);
      size = n;
    }

    /** Stable merge sort of {@code order[from, to)} by start. */
    private void sortByStart(int[] order, int[] tmp, int from, int to) {
      if (to - from < 2) {
        return;
      }
      int mid = (from + to) >>> 1;
      sortByStart(order, tmp, from, mid);
      sortByStart(order, tmp, mid, to);
      int a = from;
      int b = mid;
      int t = from;
      while (a < mid && b < to) {
        tmp[t++] = start[order[b]] < start[order[a]] ? order[b++] : order[a++];
      }
      while (a < mid) {
        tmp[t++] = order[a++];
      }
      while (b < to) {
        tmp[t++] = order[b++];
      }
      System.arraycopy(tmp, from, order, from, to - from);
    }

    /** Index of the last span with start <= t, or -1. */
    int floorIndex(long t) {
      int lo = 0;
      int hi = size - 1;
      int found = -1;
      while (lo <= hi) {
        int mid = (lo + hi) >>> 1;
        if (start[mid] <= t) {
          found = mid;
          lo = mid + 1;
        } else {
          hi = mid - 1;
        }
      }
      return found;
    }

    Match match(int i, List<String> operations) {
      if (!hasLink[i]) {
        String[] raw = rawIds == null ? null : rawIds[i];
        return new Match(
            operations.get(operation[i]), raw == null ? null : raw[0], raw == null ? null : raw[1]);
      }
      return new Match(
          operations.get(operation[i]), hex16(traceHi[i]) + hex16(traceLo[i]), hex16(spanId[i]));
    }

    private static String hex16(long v) {
      String h = Long.toHexString(v);
      return h.length() == 16 ? h : "0000000000000000".substring(h.length()) + h;
    }
  }
}
