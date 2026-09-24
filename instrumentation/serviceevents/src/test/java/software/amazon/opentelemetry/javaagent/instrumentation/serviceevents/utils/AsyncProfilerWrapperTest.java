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

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AsyncProfilerWrapper#buildStartCommand} — the async-profiler {@code start}
 * command differs by mode, so assert both {@code wall} and {@code cpu} produce the expected string
 * (no live profiler / native lib needed; the command is pure string assembly from config).
 */
class AsyncProfilerWrapperTest {

  private static AsyncProfilerWrapper wrapper(int mode, boolean memory) {
    // cpu interval 10ms, wall interval 50ms, loop 60s, alloc 512 KiB — profilerDataDir "" so
    // buildStartCommand's arg is used verbatim (constructor args:
    // cpuIntervalMs, wallIntervalMs, jfrBasePath, profilerDataDir, memoryEnabled,
    // allocIntervalBytes, loopSeconds, mode).
    return new AsyncProfilerWrapper(10, 50, "profiler-jfr", "", memory, 524288L, 60, mode);
  }

  @Test
  void buildStartCommand_wall_usesWallEventSignalAndInterval() {
    String cmd = wrapper(AsyncProfilerWrapper.MODE_WALL, false).buildStartCommand("profiler-jfr");
    assertEquals(
        "start,event=wall,signal=26,interval=50000000,threads,ann,jfr,"
            + "file=profiler-jfr-%t.jfr,loop=60s",
        cmd);
  }

  @Test
  void buildStartCommand_cpu_usesCpuEventNoSignalAndCpuInterval() {
    String cmd = wrapper(AsyncProfilerWrapper.MODE_CPU, false).buildStartCommand("profiler-jfr");
    // cpu: event=cpu, NO signal=26, cpu interval (10ms).
    assertEquals(
        "start,event=cpu,interval=10000000,threads,ann,jfr,file=profiler-jfr-%t.jfr,loop=60s", cmd);
  }

  @Test
  void buildStartCommand_memoryEnabled_appendsAllocClauseAfterInterval() {
    String cmd = wrapper(AsyncProfilerWrapper.MODE_WALL, true).buildStartCommand("profiler-jfr");
    assertEquals(
        "start,event=wall,signal=26,interval=50000000,alloc=524288,threads,ann,jfr,"
            + "file=profiler-jfr-%t.jfr,loop=60s",
        cmd);
  }
}
