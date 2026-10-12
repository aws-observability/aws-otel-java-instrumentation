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
 * <p>"Live heap" is the heap in use right after the most recent garbage collection, summed over the
 * heap pools. That is the memory the application actually holds; the current usage would also count
 * garbage not yet collected and make the guard skip windows needlessly.
 */
public final class HeapGuard {

  /** Heap figures the guard decides on; replaceable in tests. */
  public interface HeapStats {
    /** Maximum heap in bytes, or a value {@code <= 0} if unknown. */
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
          return Runtime.getRuntime().maxMemory();
        }

        @Override
        public long liveBytes() {
          long live = 0;
          for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP) {
              continue;
            }
            // Usage after the pool's last collection; pools without that figure count in full.
            MemoryUsage afterGc = pool.getCollectionUsage();
            MemoryUsage usage = afterGc != null ? afterGc : pool.getUsage();
            if (usage != null) {
              live += usage.getUsed();
            }
          }
          return live;
        }
      };

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
