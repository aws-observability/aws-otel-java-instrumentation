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

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;

/**
 * Keeps the profiler from pushing the application's heap into an OutOfMemoryError. Processing a
 * profile window temporarily needs heap proportional to the window's JFR file; the guard only lets
 * a window start when the live heap plus that estimate stays below {@link #START_LIMIT_FRACTION} of
 * the maximum heap, and reports pressure (so the window is abandoned) once the live heap exceeds
 * {@link #PRESSURE_FRACTION}.
 *
 * <p>"Live heap" is estimated per heap pool. Young pools (eden, young generation) count their usage
 * right after the last collection, since most of what they hold at any moment is short-lived
 * garbage. All other pools (survivor, old/tenured, single-pool collectors such as ZGC and
 * Shenandoah) count their current usage: their after-collection figure is only refreshed by a full
 * or mixed collection on most collectors (Serial, Parallel, and G1 between mixed cycles) and would
 * otherwise read near zero while the old generation is nearly full. Counting current usage
 * overestimates by any not-yet-collected old garbage, which can only make the guard skip a window
 * it could have processed, never let one OOM the application.
 *
 * <p>Likewise, the "maximum" the limits are taken against is the capacity of the non-young pools,
 * not the whole heap: a window's working data survives young collections, so it has to fit in the
 * old generation, which on Serial and Parallel is only about 2/3 of the heap.
 */
public final class HeapGuard {

  /** Heap figures the guard decides on; replaceable in tests. */
  public interface HeapStats {
    /** Heap capacity available to long-lived data, in bytes, or a value {@code <= 0} if unknown. */
    long maxBytes();

    /** Heap in use right after the most recent garbage collection, in bytes. */
    long liveBytes();
  }

  /**
   * Estimated peak heap to process a window, per byte of its JFR file. Measured: a 38.7 MB window
   * (381k requests, 219k samples) needed about 135 MB above the JVM's own baseline, about 3.5x;
   * rounded up for margin.
   */
  public static final long HEAP_BYTES_PER_JFR_BYTE = 4;

  /** A window may start only if live heap plus its estimate stays below this share of the max. */
  public static final double START_LIMIT_FRACTION = 0.80;

  /** A window in progress is abandoned once the live heap exceeds this share of the max. */
  public static final double PRESSURE_FRACTION = 0.90;

  /** Heap figures from the running JVM's memory pool MXBeans. */
  public static final HeapStats JVM_HEAP =
      new HeapStats() {
        @Override
        public long maxBytes() {
          // A window's working data lives for the whole window, so it ends up outside the young
          // generation: what bounds it is the capacity of the non-young pools. On Serial and
          // Parallel that is the old generation (about 2/3 of the heap); on G1, ZGC and Shenandoah
          // it is the whole heap. Pools without a defined maximum (e.g. G1's eden and survivor
          // report -1) are left out; the result never exceeds the heap maximum.
          long heapMax = Runtime.getRuntime().maxMemory();
          long capacity = 0;
          for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP || isYoungPool(pool.getName())) {
              continue;
            }
            MemoryUsage usage = pool.getUsage();
            if (usage != null && usage.getMax() > 0) {
              capacity += usage.getMax();
            }
          }
          return capacity > 0 && capacity < heapMax ? capacity : heapMax;
        }

        @Override
        public long liveBytes() {
          long live = 0;
          for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP) {
              continue;
            }
            MemoryUsage afterGc = isYoungPool(pool.getName()) ? pool.getCollectionUsage() : null;
            MemoryUsage usage = afterGc != null ? afterGc : pool.getUsage();
            if (usage != null) {
              live += usage.getUsed();
            }
          }
          return live;
        }
      };

  /**
   * Whether a heap pool holds newly allocated objects only (eden / young generation), by its
   * HotSpot pool name: "Eden Space", "PS Eden Space", "G1 Eden Space", "Par Eden Space", "ZGC Young
   * Generation". Package-private for unit testing.
   */
  static boolean isYoungPool(String name) {
    return name != null && (name.contains("Eden") || name.contains("Young"));
  }

  private final HeapStats stats;

  public HeapGuard(HeapStats stats) {
    this.stats = stats;
  }

  /** Whether a window estimated to need {@code estimatedBytes} of heap may start now. */
  public boolean canStart(long estimatedBytes) {
    long max = stats.maxBytes();
    if (max <= 0 || max == Long.MAX_VALUE) {
      return true; // unbounded or unknown heap: nothing to protect against
    }
    return stats.liveBytes() + estimatedBytes <= (long) (max * START_LIMIT_FRACTION);
  }

  /** Whether the heap is so full that a window in progress should be abandoned. */
  public boolean underPressure() {
    long max = stats.maxBytes();
    if (max <= 0 || max == Long.MAX_VALUE) {
      return false;
    }
    return stats.liveBytes() > (long) (max * PRESSURE_FRACTION);
  }

  /** A short "live X MB of max Y MB" description for log messages. */
  public String describe() {
    return "live heap "
        + (stats.liveBytes() >> 20)
        + " MB of max "
        + (stats.maxBytes() >> 20)
        + " MB";
  }
}
