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

package com.amazon.aoc.helpers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.amazon.aoc.models.xray.Entity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

public class SortUtilsTest {

  @Test
  public void testSingleLevelListSort() {
    final List<Entity> generated = generateEntities(3);
    final List<Entity> entities = new ArrayList<>();
    entities.add(0, generated.get(1));
    entities.add(1, generated.get(2));
    entities.add(2, generated.get(0));

    // Verify list is unsorted
    assertEquals(generated.get(1), entities.get(0));
    assertEquals(generated.get(2), entities.get(1));
    assertEquals(generated.get(0), entities.get(2));
    SortUtils.recursiveEntitySort(entities);

    assertEquals(3, entities.size());
    assertEquals(generated.get(0), entities.get(0));
    assertEquals(generated.get(1), entities.get(1));
    assertEquals(generated.get(2), entities.get(2));
  }

  /**
   * Expected entity structure of this test after sorting.
   *
   * <p>ent0 ent1 ent2 | ent3 ent4 ent5 | ent6 ent7
   */
  @Test
  public void testNestedEntitySort() {
    final List<Entity> generated = generateEntities(8);
    final List<Entity> topEntities = new ArrayList<>();
    final List<Entity> midEntities = new ArrayList<>();
    final List<Entity> bottomEntities = new ArrayList<>();

    topEntities.add(0, generated.get(1));
    topEntities.add(1, generated.get(2));
    topEntities.add(2, generated.get(0));
    midEntities.add(0, generated.get(5));
    midEntities.add(1, generated.get(4));
    midEntities.add(2, generated.get(3));
    bottomEntities.add(0, generated.get(7));
    bottomEntities.add(1, generated.get(6));

    generated.get(0).setSubsegments(midEntities);
    generated.get(4).setSubsegments(bottomEntities);

    SortUtils.recursiveEntitySort(topEntities);

    assertEquals(3, topEntities.size());
    assertEquals(generated.get(0), topEntities.get(0));
    assertEquals(generated.get(1), topEntities.get(1));
    assertEquals(generated.get(2), topEntities.get(2));
    assertEquals(3, topEntities.get(0).getSubsegments().size());
    assertEquals(midEntities, topEntities.get(0).getSubsegments());
    assertEquals(generated.get(3), midEntities.get(0));
    assertEquals(generated.get(4), midEntities.get(1));
    assertEquals(generated.get(5), midEntities.get(2));
    assertEquals(2, midEntities.get(1).getSubsegments().size());
    assertEquals(midEntities.get(1).getSubsegments(), bottomEntities);
    assertEquals(generated.get(6), bottomEntities.get(0));
    assertEquals(generated.get(7), bottomEntities.get(1));
  }

  @Test
  public void testInfiniteLoop() {
    Entity current = new Entity();
    List<Entity> entityList = new ArrayList<>();
    entityList.add(current);
    current.setSubsegments(entityList); // set up an infinite children loop

    SortUtils.recursiveEntitySort(entityList);

    // Not really testing anything, just making sure we don't infinite loop
    assertEquals(1, entityList.size());
  }

  private List<Entity> generateEntities(int n) {
    List<Entity> ret = new ArrayList<>();

    for (int i = 0; i < n; i++) {
      Entity entity = new Entity();
      entity.setStartTime(i);
      ret.add(entity);
    }

    return ret;
  }
}
