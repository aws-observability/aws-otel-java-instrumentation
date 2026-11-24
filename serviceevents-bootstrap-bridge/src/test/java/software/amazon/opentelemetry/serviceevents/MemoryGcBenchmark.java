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

import com.sun.management.ThreadMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Heap / GC focused benchmark for bytecode instrumentation.
 *
 * <p>Uses {@link com.sun.management.ThreadMXBean#getThreadAllocatedBytes} to measure PRECISE
 * per-thread allocation (not subject to GC noise). Also measures GC count and pause time under
 * sustained load.
 */
public class MemoryGcBenchmark {

  private static final ThreadMXBean TMB = (ThreadMXBean) ManagementFactory.getThreadMXBean();

  static {
    TMB.setThreadAllocatedMemoryEnabled(true);
  }

  static class Gc {
    final long count, timeMs;
    final long heapUsed;
    final long threadAlloc;

    Gc() {
      long c = 0, t = 0;
      for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
        c += gc.getCollectionCount();
        t += gc.getCollectionTime();
      }
      count = c;
      timeMs = t;
      MemoryUsage mu = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
      heapUsed = mu.getUsed();
      threadAlloc = TMB.getThreadAllocatedBytes(Thread.currentThread().getId());
    }
  }

  @FunctionalInterface
  interface Fn {
    void run();
  }

  /** Measure allocation precisely using the JVM thread allocation counter. Returns bytes/op. */
  static double measureAllocation(String name, long iters, Fn fn) {
    // Warmup
    for (long i = 0; i < Math.min(iters / 5, 200_000); i++) fn.run();
    System.gc();
    try {
      Thread.sleep(50);
    } catch (Exception ignored) {
    }

    long allocBefore = TMB.getThreadAllocatedBytes(Thread.currentThread().getId());
    long start = System.nanoTime();
    for (long i = 0; i < iters; i++) fn.run();
    long elapsed = System.nanoTime() - start;
    long allocAfter = TMB.getThreadAllocatedBytes(Thread.currentThread().getId());

    double bytesPerOp = (double) (allocAfter - allocBefore) / iters;
    double nsPerOp = (double) elapsed / iters;
    System.out.printf(
        "  %-58s %7.1f B/op  %7.1f ns/op  (%d iters)%n", name, bytesPerOp, nsPerOp, iters);
    return bytesPerOp;
  }

  static void sep(String s) {
    System.out.println("\n" + "=".repeat(110));
    System.out.println("  " + s);
    System.out.println("-".repeat(110));
  }

  public static void main(String[] args) throws Exception {
    System.out.println("Bytecode Instrumentation — Heap/GC Benchmark");
    System.out.println("=============================================");
    System.out.println(
        "Java: " + System.getProperty("java.version") + " / " + System.getProperty("java.vm.name"));
    System.out.println(
        "Max heap: "
            + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax() / 1024 / 1024
            + "MB");

    final long ITERS = 2_000_000;
    final long SHORT = 500_000;

    // ====================================================================
    // 1. PER-CALL ALLOCATION — isolate each hot-path component
    // ====================================================================
    sep("1. PER-CALL ALLOCATION (isolated components)");

    // Baseline: empty lambda invocation
    measureAllocation("baseline (empty lambda)", ITERS, () -> {});

    // Raw Object[] wrapper — simulates what MethodAdvice.onEnter allocates
    measureAllocation(
        "new Object[]{a,b} [advice wrapper]",
        ITERS,
        () -> {
          String fid = "com.example.Hot.method";
          Object ctx = null;
          Object[] w = new Object[] {fid, ctx};
          if (w == null) throw new Error();
        });

    // Raw Object[3] + Long.valueOf — simulates methodEnter sampled path context
    measureAllocation(
        "new Object[3]{Long,caller,Bool} [sampled ctx]",
        ITERS,
        () -> {
          Object[] ctx =
              new Object[] {Long.valueOf(System.nanoTime()), "caller.method", Boolean.TRUE};
          if (ctx == null) throw new Error();
        });

    measureAllocation(
        "Long.valueOf(nanoTime)  [boxing]",
        ITERS,
        () -> {
          Long l = Long.valueOf(System.nanoTime());
          if (l == null) throw new Error();
        });

    // CallPathEntry alloc — investigation path
    measureAllocation(
        "new CallPathEntry(fid,caller,dur)",
        ITERS,
        () -> {
          CallPathEntry e = new CallPathEntry("com.example.M.f", "com.example.C.g", 12345L);
          if (e == null) throw new Error();
        });

    // ====================================================================
    // 2. NON-SAMPLED FAST PATH — full advice simulation
    // ====================================================================
    sep("2. FULL ADVICE PATH — NOT sampled (99% case in tier3)");

    ServiceEventsDataStore.resetState();
    ServiceEventsDataStore.setCurrentOperation("POST /echo");
    for (int i = 0; i < 1100; i++) {
      Object c = ServiceEventsDataStore.methodEnter("com.example.Hot.method");
      if (c != null) ServiceEventsDataStore.methodExit("com.example.Hot.method", c, null);
    }

    measureAllocation(
        "methodEnter alone (NOT sampled)",
        ITERS,
        () -> {
          ServiceEventsDataStore.methodEnter("com.example.Hot.method");
        });

    measureAllocation(
        "full advice path (NOT sampled)",
        ITERS,
        () -> {
          String fid = "com.example.Hot.method";
          Object ctx = ServiceEventsDataStore.methodEnter(fid);
          Object[] w = new Object[] {fid, ctx}; // advice wrapper
          if (w[1] == null) return;
          ServiceEventsDataStore.methodExit((String) w[0], w[1], null);
        });

    // ====================================================================
    // 3. INVESTIGATION ACTIVE — every request in Coral/Spring
    // ====================================================================
    sep("3. FULL ADVICE PATH — investigation ACTIVE (every request)");

    ServiceEventsDataStore.resetState();
    ServiceEventsDataStore.setCurrentOperation("POST /echo");
    ServiceEventsDataStore.beginInvestigation();
    for (int i = 0; i < 1100; i++) {
      Object c = ServiceEventsDataStore.methodEnter("com.example.Inv.method");
      if (c != null) ServiceEventsDataStore.methodExit("com.example.Inv.method", c, null);
    }

    measureAllocation(
        "advice path (investigation ON, not sampled)",
        SHORT,
        () -> {
          String fid = "com.example.Inv.method";
          Object ctx = ServiceEventsDataStore.methodEnter(fid);
          Object[] w = new Object[] {fid, ctx};
          if (w[1] == null) return;
          ServiceEventsDataStore.methodExit((String) w[0], w[1], null);
        });

    ServiceEventsDataStore.clearInvestigation();

    // ====================================================================
    // 4. SAMPLED PATH
    // ====================================================================
    sep("4. FULL ADVICE PATH — SAMPLED (record duration)");

    measureAllocation(
        "advice path (SAMPLED) — swap every iter",
        SHORT,
        () -> {
          ServiceEventsDataStore.getAndSwapAggregations();
          ServiceEventsDataStore.setCurrentOperation("POST /echo");
          String fid = "com.example.Sampled.method";
          Object ctx = ServiceEventsDataStore.methodEnter(fid);
          Object[] w = new Object[] {fid, ctx};
          if (w[1] == null) return;
          ServiceEventsDataStore.methodExit((String) w[0], w[1], null);
        });

    // ====================================================================
    // 5. EXCEPTION PATH
    // ====================================================================
    sep("5. EXCEPTION PATH (StringWriter + PrintWriter + stack trace)");

    Throwable testEx = new RuntimeException("test error");

    measureAllocation(
        "StringWriter+PrintWriter+printStackTrace",
        50_000,
        () -> {
          java.io.StringWriter sw = new java.io.StringWriter();
          testEx.printStackTrace(new java.io.PrintWriter(sw));
          String s = sw.toString();
          if (s.isEmpty()) throw new Error();
        });

    // ====================================================================
    // 6. ENDPOINT AGGREGATION (per-request) — durations list boxing
    // ====================================================================
    sep("6. ENDPOINT recordDuration (per-request path)");

    ServiceEventsDataStore.resetState();
    measureAllocation(
        "recordEndpointRequest (accumulates Long in list)",
        SHORT,
        () -> {
          ServiceEventsDataStore.recordEndpointRequest(
              "POST /echo", "/echo", "POST", 200, 123456789L, null, null, "Echo");
        });

    // ====================================================================
    // 7. SUSTAINED LOAD — measure real GC impact
    // ====================================================================
    sep("7. SUSTAINED LOAD — GC count / GC time / heap growth");

    sustainedLoad("NOT sampled (tier3), no investigation", 5_000_000, false, false);

    sustainedLoad("NOT sampled (tier3), investigation ACTIVE", 2_000_000, false, true);

    sustainedLoad("SAMPLED path with swap between runs", 500_000, true, false);

    // ====================================================================
    // 8. CONCURRENT SUSTAINED LOAD — realistic production pattern
    // ====================================================================
    sep("8. CONCURRENT SUSTAINED LOAD (8 threads, 500K calls each)");

    concurrentLoad(8, 500_000, false, true);

    // ====================================================================
    // 9. HEAP RETENTION (growth of aggregation maps over time)
    // ====================================================================
    sep("9. HEAP RETENTION — unique functions growing aggregation map");

    heapRetention();

    ServiceEventsDataStore.resetState();
  }

  static void sustainedLoad(String name, long calls, boolean sampled, boolean investigating) {
    ServiceEventsDataStore.resetState();
    ServiceEventsDataStore.setCurrentOperation("POST /echo");
    if (investigating) ServiceEventsDataStore.beginInvestigation();
    if (!sampled) {
      // Burn to tier 3
      for (int i = 0; i < 1100; i++) {
        Object c = ServiceEventsDataStore.methodEnter("com.example.Load.method");
        if (c != null) ServiceEventsDataStore.methodExit("com.example.Load.method", c, null);
      }
    }

    System.gc();
    try {
      Thread.sleep(200);
    } catch (Exception ignored) {
    }
    Gc before = new Gc();
    long startNs = System.nanoTime();

    if (sampled) {
      for (long i = 0; i < calls; i++) {
        if ((i & 127L) == 0L) ServiceEventsDataStore.getAndSwapAggregations();
        String fid = "com.example.Load.method";
        Object ctx = ServiceEventsDataStore.methodEnter(fid);
        Object[] w = new Object[] {fid, ctx};
        if (w[1] != null) ServiceEventsDataStore.methodExit((String) w[0], w[1], null);
      }
    } else {
      for (long i = 0; i < calls; i++) {
        String fid = "com.example.Load.method";
        Object ctx = ServiceEventsDataStore.methodEnter(fid);
        Object[] w = new Object[] {fid, ctx};
        if (w[1] != null) ServiceEventsDataStore.methodExit((String) w[0], w[1], null);
      }
    }

    long elapsed = System.nanoTime() - startNs;
    Gc after = new Gc();
    long allocDelta = after.threadAlloc - before.threadAlloc;

    System.out.printf(
        "  %-55s %,10d calls in %6.1fms (%.1fns/call)  GC %d/%dms  alloc %.1f MB (%.1f B/op)  heap Δ %+.1fMB%n",
        name,
        calls,
        elapsed / 1e6,
        (double) elapsed / calls,
        after.count - before.count,
        after.timeMs - before.timeMs,
        allocDelta / 1024.0 / 1024.0,
        (double) allocDelta / calls,
        (after.heapUsed - before.heapUsed) / 1024.0 / 1024.0);

    if (investigating) ServiceEventsDataStore.clearInvestigation();
  }

  static void concurrentLoad(
      int nThreads, long itersPerThread, boolean sampled, boolean investigating) throws Exception {
    ServiceEventsDataStore.resetState();

    Thread[] threads = new Thread[nThreads];
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(nThreads);
    AtomicLong totalAlloc = new AtomicLong();

    for (int t = 0; t < nThreads; t++) {
      final int tid = t;
      threads[t] =
          new Thread(
              () -> {
                ServiceEventsDataStore.setCurrentOperation("OP" + (tid % 4));
                if (investigating) ServiceEventsDataStore.beginInvestigation();
                // Warmup & burn to tier3
                String fid = "com.example.S" + (tid % 4) + ".m";
                for (int i = 0; i < 1100; i++) {
                  Object c = ServiceEventsDataStore.methodEnter(fid);
                  if (c != null) ServiceEventsDataStore.methodExit(fid, c, null);
                }
                try {
                  startLatch.await();
                } catch (InterruptedException ignored) {
                }

                long allocBefore = TMB.getThreadAllocatedBytes(Thread.currentThread().getId());
                for (long i = 0; i < itersPerThread; i++) {
                  Object ctx = ServiceEventsDataStore.methodEnter(fid);
                  Object[] w = new Object[] {fid, ctx};
                  if (w[1] != null) ServiceEventsDataStore.methodExit((String) w[0], w[1], null);
                }
                long allocAfter = TMB.getThreadAllocatedBytes(Thread.currentThread().getId());
                totalAlloc.addAndGet(allocAfter - allocBefore);
                doneLatch.countDown();
              },
              "load-" + t);
      threads[t].start();
    }

    // Wait for warmups to settle
    try {
      Thread.sleep(500);
    } catch (Exception ignored) {
    }
    System.gc();
    try {
      Thread.sleep(200);
    } catch (Exception ignored) {
    }

    Gc before = new Gc();
    long startNs = System.nanoTime();
    startLatch.countDown();
    doneLatch.await();
    long elapsed = System.nanoTime() - startNs;
    Gc after = new Gc();

    long totalCalls = nThreads * itersPerThread;
    System.out.printf(
        "  %d threads x %,d calls = %,d total in %.1fms  GC %d/%dms  alloc %.1f MB (%.1f B/op)  heap Δ %+.1fMB%n",
        nThreads,
        itersPerThread,
        totalCalls,
        elapsed / 1e6,
        after.count - before.count,
        after.timeMs - before.timeMs,
        totalAlloc.get() / 1024.0 / 1024.0,
        (double) totalAlloc.get() / totalCalls,
        (after.heapUsed - before.heapUsed) / 1024.0 / 1024.0);
  }

  /** Simulate a service with 500 unique methods; measure retained memory in aggregation map. */
  static void heapRetention() {
    ServiceEventsDataStore.resetState();
    ServiceEventsDataStore.setCurrentOperation("POST /echo");

    System.gc();
    try {
      Thread.sleep(200);
    } catch (Exception ignored) {
    }
    long heapBase = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();

    for (int i = 0; i < 500; i++) {
      // Each function gets sampled (tier1) at least 101 times to force AggregationData creation
      String fid = "com.example.Service" + i + ".method";
      for (int j = 0; j < 101; j++) {
        Object c = ServiceEventsDataStore.methodEnter(fid);
        if (c != null) ServiceEventsDataStore.methodExit(fid, c, null);
      }
    }

    System.gc();
    try {
      Thread.sleep(200);
    } catch (Exception ignored) {
    }
    long heapAfter500 = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();

    // Simulate multiple operations (real services have many endpoints)
    for (int opIdx = 0; opIdx < 10; opIdx++) {
      ServiceEventsDataStore.setCurrentOperation("OP" + opIdx);
      for (int i = 0; i < 500; i++) {
        String fid = "com.example.Service" + i + ".method";
        for (int j = 0; j < 101; j++) {
          Object c = ServiceEventsDataStore.methodEnter(fid);
          if (c != null) ServiceEventsDataStore.methodExit(fid, c, null);
        }
      }
    }

    System.gc();
    try {
      Thread.sleep(200);
    } catch (Exception ignored) {
    }
    long heapAfterMulti = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();

    System.out.printf("  Heap baseline:                    %.2f MB%n", heapBase / 1024.0 / 1024.0);
    System.out.printf(
        "  After 500 funcs × 1 operation:    %.2f MB  (+%.2f MB, ~%.0f B/func)%n",
        heapAfter500 / 1024.0 / 1024.0,
        (heapAfter500 - heapBase) / 1024.0 / 1024.0,
        (double) (heapAfter500 - heapBase) / 500);
    System.out.printf(
        "  After 500 funcs × 11 operations:  %.2f MB  (+%.2f MB vs base)%n",
        heapAfterMulti / 1024.0 / 1024.0, (heapAfterMulti - heapBase) / 1024.0 / 1024.0);

    // Verify swap releases the memory
    ServiceEventsDataStore.getAndSwapAggregations();
    System.gc();
    try {
      Thread.sleep(200);
    } catch (Exception ignored) {
    }
    long heapAfterSwap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    System.out.printf(
        "  After getAndSwapAggregations:     %.2f MB  (reclaimed %.2f MB)%n",
        heapAfterSwap / 1024.0 / 1024.0, (heapAfterMulti - heapAfterSwap) / 1024.0 / 1024.0);
  }
}
