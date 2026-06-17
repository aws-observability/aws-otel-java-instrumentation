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

package software.amazon.opentelemetry.serviceevents;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Benchmark for the Java bytecode instrumentation hot path (current, unoptimized state).
 *
 * <p>Simulates what happens for every instrumented method call in production: ByteBuddy
 * MethodAdvice.onEnter → ServiceEventsDataStore.methodEnter → (method executes) →
 * ServiceEventsDataStore.methodExit → MethodAdvice.onExit.
 */
public class BytecodeHotPathBenchmark {

  @FunctionalInterface
  interface BenchFn {
    void run();
  }

  @FunctionalInterface
  interface ThreadBenchFn {
    void run(int tid);
  }

  static class GcSnapshot {
    final long gcCount;
    final long gcTimeMs;
    final long heapUsedBytes;

    GcSnapshot() {
      long count = 0, time = 0;
      for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
        count += gc.getCollectionCount();
        time += gc.getCollectionTime();
      }
      gcCount = count;
      gcTimeMs = time;
      heapUsedBytes = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
  }

  static void bench(String name, long iters, BenchFn fn) {
    // Warmup
    for (long i = 0; i < Math.min(iters / 5, 200_000); i++) fn.run();
    System.gc();
    try {
      Thread.sleep(100);
    } catch (Exception ignored) {
    }

    long[] lats = new long[(int) iters];
    GcSnapshot before = new GcSnapshot();
    for (int i = 0; i < iters; i++) {
      long s = System.nanoTime();
      fn.run();
      lats[i] = System.nanoTime() - s;
    }
    GcSnapshot after = new GcSnapshot();

    Arrays.sort(lats);
    double sum = 0;
    for (long l : lats) sum += l;

    long heapDelta = Math.max(0, after.heapUsedBytes - before.heapUsedBytes);

    System.out.printf(
        "  %-58s avg=%7.1fns  p50=%7.1fns  p99=%8.1fns  |  GC %2d/%4dms  |  alloc %6.1fB/op%n",
        name,
        sum / iters,
        (double) lats[(int) (iters / 2)],
        (double) lats[(int) (iters * 0.99)],
        after.gcCount - before.gcCount,
        after.gcTimeMs - before.gcTimeMs,
        (double) heapDelta / iters);
  }

  static void benchThreads(String name, int nThreads, long itersPerThread, ThreadBenchFn fn) {
    long[][] allLats = new long[nThreads][];
    Thread[] threads = new Thread[nThreads];
    AtomicBoolean go = new AtomicBoolean(false);

    for (int t = 0; t < nThreads; t++) {
      final int tid = t;
      allLats[tid] = new long[(int) itersPerThread];
      // Warmup per thread
      for (long i = 0; i < Math.min(itersPerThread / 10, 10_000); i++) fn.run(tid);
      threads[t] =
          new Thread(
              () -> {
                while (!go.get()) Thread.yield();
                for (int i = 0; i < itersPerThread; i++) {
                  long s = System.nanoTime();
                  fn.run(tid);
                  allLats[tid][i] = System.nanoTime() - s;
                }
              },
              "bench-" + tid);
      threads[t].start();
    }

    System.gc();
    try {
      Thread.sleep(100);
    } catch (Exception ignored) {
    }
    GcSnapshot before = new GcSnapshot();
    go.set(true);
    for (Thread t : threads) {
      try {
        t.join();
      } catch (InterruptedException ignored) {
      }
    }
    GcSnapshot after = new GcSnapshot();

    long[] merged = new long[(int) (nThreads * itersPerThread)];
    int off = 0;
    for (long[] a : allLats) {
      System.arraycopy(a, 0, merged, off, a.length);
      off += a.length;
    }
    Arrays.sort(merged);
    long total = merged.length;
    double sum = 0;
    for (long l : merged) sum += l;

    System.out.printf(
        "  %-58s avg=%7.1fns  p50=%7.1fns  p99=%8.1fns  |  GC %2d/%4dms%n",
        name,
        sum / total,
        (double) merged[(int) (total / 2)],
        (double) merged[(int) (total * 0.99)],
        after.gcCount - before.gcCount,
        after.gcTimeMs - before.gcTimeMs);
  }

  static void sep(String s) {
    System.out.println("\n" + "=".repeat(130));
    System.out.println("  " + s);
    System.out.println("-".repeat(130));
  }

  public static void main(String[] args) {
    System.out.println("Java Bytecode Instrumentation Hot Path — Current State Benchmark");
    System.out.println("=================================================================");
    System.out.println("Threads: " + Runtime.getRuntime().availableProcessors());
    System.out.println(
        "Java: " + System.getProperty("java.version") + " / " + System.getProperty("java.vm.name"));

    final long ITERS = 2_000_000;
    final long SHORT = 500_000;
    final long MT_PER = 100_000;

    // ================================================================
    // 1. NON-SAMPLED FAST PATH (the 99% case in tier 3)
    // ================================================================
    sep("1. NON-SAMPLED FAST PATH (tier3 — 99% of all method calls)");

    ServiceEventsDataStore.resetState();
    ServiceEventsDataStore.setCurrentOperation("POST /echo");
    // Burn to tier 3
    for (int i = 0; i < 1100; i++) {
      Object ctx = ServiceEventsDataStore.methodEnter("com.example.Hot.method");
      if (ctx != null) ServiceEventsDataStore.methodExit("com.example.Hot.method", ctx, null);
    }

    bench(
        "ServiceEventsDataStore.methodEnter (NOT sampled)",
        ITERS,
        () -> {
          ServiceEventsDataStore.methodEnter("com.example.Hot.method");
        });

    // Simulate MethodAdvice: onEnter allocates Object[] wrapper every call
    bench(
        "FULL advice path (NOT sampled) — with Object[] wrapper",
        ITERS,
        () -> {
          String functionId = "com.example.Hot.method";
          Object context = ServiceEventsDataStore.methodEnter(functionId);
          Object[] enterData = new Object[] {functionId, context}; // ← advice allocation
          if (enterData[1] == null) return;
          ServiceEventsDataStore.methodExit((String) enterData[0], enterData[1], null);
        });

    // ================================================================
    // 2. NON-SAMPLED WITH INVESTIGATION ACTIVE (every request in Coral)
    // ================================================================
    sep("2. NON-SAMPLED WITH INVESTIGATION ACTIVE (every HTTP request)");

    ServiceEventsDataStore.resetState();
    ServiceEventsDataStore.setCurrentOperation("POST /echo");
    ServiceEventsDataStore.beginInvestigation();
    for (int i = 0; i < 1100; i++) {
      Object ctx = ServiceEventsDataStore.methodEnter("com.example.Inv.method");
      if (ctx != null) ServiceEventsDataStore.methodExit("com.example.Inv.method", ctx, null);
    }

    bench(
        "advice path (NOT sampled, investigation ACTIVE)",
        SHORT,
        () -> {
          String fid = "com.example.Inv.method";
          Object ctx = ServiceEventsDataStore.methodEnter(fid);
          Object[] enterData = new Object[] {fid, ctx};
          if (enterData[1] == null) return;
          ServiceEventsDataStore.methodExit((String) enterData[0], enterData[1], null);
        });

    ServiceEventsDataStore.clearInvestigation();

    // ================================================================
    // 3. SAMPLED PATH
    // ================================================================
    sep("3. SAMPLED PATH (enter + record + exit)");

    bench(
        "advice path (SAMPLED, tier1) — context swap every iter",
        SHORT,
        () -> {
          ServiceEventsDataStore.getAndSwapAggregations();
          ServiceEventsDataStore.setCurrentOperation("POST /echo");
          String fid = "com.example.Sampled.method";
          Object ctx = ServiceEventsDataStore.methodEnter(fid);
          Object[] enterData = new Object[] {fid, ctx};
          if (enterData[1] == null) return;
          ServiceEventsDataStore.methodExit((String) enterData[0], enterData[1], null);
        });

    // ================================================================
    // 4. recordMethodInvocation CONTENTION (synchronized recordDuration)
    // ================================================================
    sep("4. synchronized recordDuration CONTENTION (main write bottleneck)");

    for (int threads : new int[] {1, 2, 4, 8, 16}) {
      if (threads > Runtime.getRuntime().availableProcessors() * 2) break;
      ServiceEventsDataStore.resetState();
      ServiceEventsDataStore.setCurrentOperation("POST /echo");
      ServiceEventsDataStore.methodEnter("com.example.Shared.method");

      benchThreads(
          "recordMethodInvocation (" + threads + "T, SAME func)",
          threads,
          MT_PER,
          (tid) ->
              ServiceEventsDataStore.recordMethodInvocation(
                  "POST /echo", "com.example.Shared.method", 123456, "caller", null));
    }

    System.out.println();
    for (int threads : new int[] {1, 2, 4, 8, 16}) {
      if (threads > Runtime.getRuntime().availableProcessors() * 2) break;
      ServiceEventsDataStore.resetState();
      for (int t = 0; t < threads; t++) {
        ServiceEventsDataStore.setCurrentOperation("OP" + (t % 5));
        ServiceEventsDataStore.methodEnter("com.example.S" + t + ".m");
      }

      benchThreads(
          "recordMethodInvocation (" + threads + "T, DIFF funcs)",
          threads,
          MT_PER,
          (tid) ->
              ServiceEventsDataStore.recordMethodInvocation(
                  "OP" + (tid % 5), "com.example.S" + tid + ".m", 123456, "caller", null));
    }

    // ================================================================
    // 5. MULTI-THREADED FULL ENTER+EXIT
    // ================================================================
    sep("5. MULTI-THREADED FULL ENTER+EXIT (end-to-end contention)");

    for (int threads : new int[] {1, 2, 4, 8, 16}) {
      if (threads > Runtime.getRuntime().availableProcessors() * 2) break;
      ServiceEventsDataStore.resetState();

      benchThreads(
          "full advice path sampled (" + threads + "T, SAME func)",
          threads,
          MT_PER,
          (tid) -> {
            ServiceEventsDataStore.setCurrentOperation("POST /echo");
            ServiceEventsDataStore.getAndSwapAggregations();
            String fid = "com.example.Shared.method";
            Object ctx = ServiceEventsDataStore.methodEnter(fid);
            Object[] enterData = new Object[] {fid, ctx};
            if (enterData[1] == null) return;
            ServiceEventsDataStore.methodExit((String) enterData[0], enterData[1], null);
          });
    }

    // ================================================================
    // 6. GC PRESSURE — sustained
    // ================================================================
    sep("6. GC PRESSURE — 5M non-sampled calls sustained");

    ServiceEventsDataStore.resetState();
    ServiceEventsDataStore.setCurrentOperation("POST /echo");
    for (int i = 0; i < 1100; i++) {
      Object ctx = ServiceEventsDataStore.methodEnter("com.example.GcTest.method");
      if (ctx != null) ServiceEventsDataStore.methodExit("com.example.GcTest.method", ctx, null);
    }

    System.gc();
    try {
      Thread.sleep(200);
    } catch (Exception ignored) {
    }
    GcSnapshot before = new GcSnapshot();
    long start = System.nanoTime();
    for (long i = 0; i < 5_000_000L; i++) {
      String fid = "com.example.GcTest.method";
      Object ctx = ServiceEventsDataStore.methodEnter(fid);
      Object[] enterData = new Object[] {fid, ctx};
      if (enterData[1] != null) {
        ServiceEventsDataStore.methodExit((String) enterData[0], enterData[1], null);
      }
    }
    long elapsed = System.nanoTime() - start;
    GcSnapshot after = new GcSnapshot();
    System.out.printf(
        "  5M non-sampled advice paths: %.1fms  (%.1fns/call)  GC %d/%dms  heap +%.1fKB%n",
        elapsed / 1e6,
        (double) elapsed / 5_000_000,
        after.gcCount - before.gcCount,
        after.gcTimeMs - before.gcTimeMs,
        (after.heapUsedBytes - before.heapUsedBytes) / 1024.0);

    ServiceEventsDataStore.resetState();
  }
}
