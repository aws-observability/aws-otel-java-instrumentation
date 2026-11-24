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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link HeapGuard#JVM_HEAP} must see the application's live data whichever garbage collector is in
 * use. On Serial, Parallel and G1 the old generation's after-collection usage stays near zero until
 * a full or mixed collection, so a guard reading only that figure would let a window start on a
 * nearly full heap. Each case runs in a child JVM with the given collector.
 */
class HeapGuardJvmTest {

  /**
   * Child JVM: holds about 100 MB of live data (promoted to the old generation), prints liveBytes.
   */
  public static void main(String[] args) {
    List<byte[]> live = new ArrayList<>();
    for (int i = 0; i < 400; i++) {
      live.add(new byte[256 << 10]);
      for (int j = 0; j < 20; j++) {
        byte[] garbage = new byte[64 << 10];
        garbage[0] = 1;
      }
    }
    // Churn short-lived garbage so the live data is promoted out of eden by young collections
    // only (no full GC, which would refresh the stale after-collection figures and hide the bug).
    for (int j = 0; j < 30_000; j++) {
      byte[] garbage = new byte[64 << 10];
      garbage[0] = 1;
    }
    System.out.println(
        "LIVE_BYTES="
            + HeapGuard.JVM_HEAP.liveBytes()
            + " MAX_BYTES="
            + HeapGuard.JVM_HEAP.maxBytes()
            + " HEAP_MAX="
            + Runtime.getRuntime().maxMemory()
            + " HELD="
            + live.size());
  }

  /** LIVE_BYTES, MAX_BYTES and HEAP_MAX printed by a child JVM using {@code gcFlag}. */
  private static long[] heapFiguresUnder(String gcFlag) throws Exception {
    Process p =
        new ProcessBuilder(
                Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                gcFlag,
                "-Xmx256m",
                "-cp",
                System.getProperty("java.class.path"),
                HeapGuardJvmTest.class.getName())
            .redirectErrorStream(true)
            .start();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (InputStream in = p.getInputStream()) {
      byte[] buf = new byte[8192];
      for (int n; (n = in.read(buf)) > 0; ) {
        out.write(buf, 0, n);
      }
    }
    assertEquals(0, p.waitFor(), out.toString());
    String text = out.toString();
    return new long[] {
      field(text, "LIVE_BYTES="), field(text, "MAX_BYTES="), field(text, "HEAP_MAX=")
    };
  }

  private static long field(String text, String key) {
    int i = text.indexOf(key);
    assertTrue(i >= 0, text);
    return Long.parseLong(text.substring(i + key.length(), text.indexOf(' ', i)));
  }

  @Test
  void jvmHeap_seesOldGenerationLiveData_onSerialParallelAndG1() throws Exception {
    long held = 400L * (256 << 10); // 100 MiB
    for (String gc : new String[] {"-XX:+UseSerialGC", "-XX:+UseParallelGC", "-XX:+UseG1GC"}) {
      long seen = heapFiguresUnder(gc)[0];
      assertTrue(seen >= held, gc + ": guard saw " + (seen >> 20) + " MB, app holds 100 MB");
    }
  }

  /**
   * The limits are taken against the space long-lived data can occupy: on Serial and Parallel the
   * old generation (about 2/3 of the heap), on G1 the whole heap.
   */
  @Test
  void jvmHeap_capacityIsOldGenerationOnGenerationalCollectors() throws Exception {
    for (String gc : new String[] {"-XX:+UseSerialGC", "-XX:+UseParallelGC"}) {
      long[] f = heapFiguresUnder(gc);
      assertTrue(
          f[1] < f[2] * 0.8,
          gc
              + ": capacity "
              + (f[1] >> 20)
              + " MB should exclude the young generation of a "
              + (f[2] >> 20)
              + " MB heap");
      assertTrue(f[1] > f[2] * 0.5, gc + ": capacity " + (f[1] >> 20) + " MB too small");
    }
    long[] g1 = heapFiguresUnder("-XX:+UseG1GC");
    assertEquals(g1[2], g1[1], "G1: capacity is the whole heap");
  }

  @Test
  void isYoungPool_matchesHotSpotYoungPoolNames() {
    for (String young :
        new String[] {
          "Eden Space", "PS Eden Space", "G1 Eden Space", "Par Eden Space", "ZGC Young Generation"
        }) {
      assertTrue(HeapGuard.isYoungPool(young), young);
    }
    for (String old :
        new String[] {
          "Tenured Gen",
          "PS Old Gen",
          "G1 Old Gen",
          "Survivor Space",
          "ZHeap",
          "Shenandoah",
          "ZGC Old Generation"
        }) {
      assertFalse(HeapGuard.isYoungPool(old), old);
    }
  }
}
