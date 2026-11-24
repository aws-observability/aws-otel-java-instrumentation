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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests the call_path cap in {@link InvestigationData}. Mirrors Python/JS keep-first truncation.
 */
class InvestigationDataTest {

  @Test
  void addEntry_capsCallPathAndAppendsSingleTruncationSentinelOnOverflow() {
    InvestigationData inv = new InvestigationData();
    int max = InvestigationData.MAX_CALL_PATH_ENTRIES;

    // Record well past the cap; every real frame has a non-zero duration.
    for (int i = 0; i < max + 50; i++) {
      String caller = i == 0 ? null : "func-" + (i - 1);
      inv.addEntry("func-" + i, caller, 1000L);
    }

    List<CallPathEntry> path = inv.getCallPath();
    // Keep-first: MAX real frames + exactly one sentinel, regardless of how far past the cap we
    // went.
    assertEquals(max + 1, path.size());

    // A below-cap frame is a normal entry.
    assertEquals("func-0", path.get(0).functionId);
    assertEquals("func-" + (max - 1), path.get(max - 1).functionId);
    assertEquals(1000L, path.get(max - 1).durationNs);

    // The overflow frame is the sentinel with durationNs=0, which trips is_partial in the emitter.
    CallPathEntry sentinel = path.get(max);
    assertEquals(InvestigationData.CALL_PATH_TRUNCATION_SENTINEL, sentinel.functionId);
    assertNull(sentinel.caller);
    assertEquals(0L, sentinel.durationNs);
  }

  @Test
  void addEntry_belowCapKeepsEveryFrame() {
    InvestigationData inv = new InvestigationData();

    inv.addEntry("a", null, 10L);
    inv.addEntry("b", "a", 20L);
    inv.addEntry("c", "b", 30L);

    List<CallPathEntry> path = inv.getCallPath();
    assertEquals(3, path.size());
    assertEquals("a", path.get(0).functionId);
    assertEquals("c", path.get(2).functionId);
    assertEquals(30L, path.get(2).durationNs);
  }
}
