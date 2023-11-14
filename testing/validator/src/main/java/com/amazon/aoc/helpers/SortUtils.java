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

import com.amazon.aoc.models.xray.Entity;
import java.util.List;

public final class SortUtils {
  private static final int MAX_RESURSIVE_DEPTH = 10;

  /**
   * Given a list of entities, which are X-Ray segments or subsegments, recursively sort each of
   * their children subsegments by start time, then sort the given list itself by start time.
   *
   * @param entities - list of X-Ray entities to sort recursively. Modified in place.
   */
  public static void recursiveEntitySort(List<Entity> entities) {
    recursiveEntitySort(entities, 0);
  }

  private static void recursiveEntitySort(List<Entity> entities, int depth) {
    if (entities == null || entities.size() == 0 || depth >= MAX_RESURSIVE_DEPTH) {
      return;
    }
    int currDepth = depth + 1;

    for (Entity entity : entities) {
      if (entity.getSubsegments() != null && !entity.getSubsegments().isEmpty()) {
        recursiveEntitySort(entity.getSubsegments(), currDepth);
      }
    }

    entities.sort(
        (entity1, entity2) -> {
          if (entity1.getStartTime() == entity2.getStartTime()) {
            return 0;
          }

          return entity1.getStartTime() < entity2.getStartTime() ? -1 : 1;
        });
  }
}
