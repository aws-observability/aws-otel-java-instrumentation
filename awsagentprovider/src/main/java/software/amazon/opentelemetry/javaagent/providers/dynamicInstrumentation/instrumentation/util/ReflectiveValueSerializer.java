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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.instrumentation.util;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.CaptureConfiguration;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.snapshot.CapturedValue;

/** Reflection-based object graph walker that produces CapturedValue trees. */
public final class ReflectiveValueSerializer {

  private static final long TIMEOUT_NS = 200_000_000L; // 200ms in nanoseconds

  private ReflectiveValueSerializer() {
    // Utility class
  }

  public static CapturedValue serialize(Object value, CaptureConfiguration config) {
    long deadline = System.nanoTime() + TIMEOUT_NS;
    IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
    return serializeInternal(value, null, config, 0, 0, visited, deadline);
  }

  private static CapturedValue serializeInternal(
      Object value,
      String declaredType,
      CaptureConfiguration config,
      int objectDepth,
      int collectionDepth,
      IdentityHashMap<Object, Boolean> visited,
      long deadline) {

    if (System.nanoTime() > deadline) {
      return CapturedValue.notCaptured(
          getTypeName(value, declaredType), CapturedValue.NotCapturedReason.TIMEOUT);
    }

    if (value == null) {
      return CapturedValue.ofNull(declaredType != null ? declaredType : "unknown");
    }

    String typeName = value.getClass().getName();

    if (!isPrimitiveOrWrapper(value) && !(value instanceof String) && visited.containsKey(value)) {
      return CapturedValue.notCaptured(typeName, CapturedValue.NotCapturedReason.DEPTH);
    }

    if (isPrimitiveOrWrapper(value)) {
      return CapturedValue.ofPrimitive(typeName, String.valueOf(value));
    }

    if (value instanceof String) {
      String str = (String) value;
      int maxLen = config.getMaxStringLength();
      boolean truncated = str.length() > maxLen;
      String truncatedStr = truncated ? str.substring(0, maxLen) : str;
      return CapturedValue.ofString(truncatedStr, truncated, str.length());
    }

    visited.put(value, Boolean.TRUE);

    try {
      if (value.getClass().isArray()) {
        if (collectionDepth >= config.getMaxCollectionDepth()) {
          return CapturedValue.notCaptured(typeName, CapturedValue.NotCapturedReason.DEPTH);
        }
        return serializeArray(value, config, objectDepth, collectionDepth, visited, deadline);
      }

      if (value instanceof Collection) {
        if (collectionDepth >= config.getMaxCollectionDepth()) {
          return CapturedValue.notCaptured(typeName, CapturedValue.NotCapturedReason.DEPTH);
        }
        return serializeCollection(
            (Collection<?>) value, config, objectDepth, collectionDepth, visited, deadline);
      }

      if (value instanceof Map) {
        if (collectionDepth >= config.getMaxCollectionDepth()) {
          return CapturedValue.notCaptured(typeName, CapturedValue.NotCapturedReason.DEPTH);
        }
        return serializeMap(
            (Map<?, ?>) value, config, objectDepth, collectionDepth, visited, deadline);
      }

      if (objectDepth >= config.getMaxObjectDepth()) {
        return CapturedValue.notCaptured(typeName, CapturedValue.NotCapturedReason.DEPTH);
      }
      return serializeObject(value, config, objectDepth, collectionDepth, visited, deadline);

    } finally {
      visited.remove(value);
    }
  }

  private static CapturedValue serializeArray(
      Object array,
      CaptureConfiguration config,
      int objectDepth,
      int collectionDepth,
      IdentityHashMap<Object, Boolean> visited,
      long deadline) {

    int length = Array.getLength(array);
    String typeName = array.getClass().getName();
    List<CapturedValue> elements = new ArrayList<>();

    int maxItems = Math.min(length, config.getMaxCollectionWidth());
    for (int i = 0; i < maxItems; i++) {
      if (System.nanoTime() > deadline) {
        return CapturedValue.notCaptured(typeName, CapturedValue.NotCapturedReason.TIMEOUT);
      }
      Object element = Array.get(array, i);
      elements.add(
          serializeInternal(
              element, null, config, objectDepth, collectionDepth + 1, visited, deadline));
    }

    return CapturedValue.ofCollection(typeName, elements, length);
  }

  private static CapturedValue serializeCollection(
      Collection<?> collection,
      CaptureConfiguration config,
      int objectDepth,
      int collectionDepth,
      IdentityHashMap<Object, Boolean> visited,
      long deadline) {

    String typeName = collection.getClass().getName();
    List<CapturedValue> elements = new ArrayList<>();

    int count = 0;
    int maxItems = config.getMaxCollectionWidth();
    for (Object element : collection) {
      if (count >= maxItems) {
        break;
      }
      if (System.nanoTime() > deadline) {
        return CapturedValue.notCaptured(typeName, CapturedValue.NotCapturedReason.TIMEOUT);
      }
      elements.add(
          serializeInternal(
              element, null, config, objectDepth, collectionDepth + 1, visited, deadline));
      count++;
    }

    return CapturedValue.ofCollection(typeName, elements, collection.size());
  }

  private static CapturedValue serializeMap(
      Map<?, ?> map,
      CaptureConfiguration config,
      int objectDepth,
      int collectionDepth,
      IdentityHashMap<Object, Boolean> visited,
      long deadline) {

    String typeName = map.getClass().getName();
    List<CapturedValue.MapEntry> entries = new ArrayList<>();

    int count = 0;
    int maxItems = config.getMaxCollectionWidth();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (count >= maxItems) {
        break;
      }
      if (System.nanoTime() > deadline) {
        return CapturedValue.notCaptured(typeName, CapturedValue.NotCapturedReason.TIMEOUT);
      }
      CapturedValue key =
          serializeInternal(
              entry.getKey(), null, config, objectDepth, collectionDepth + 1, visited, deadline);
      CapturedValue value =
          serializeInternal(
              entry.getValue(), null, config, objectDepth, collectionDepth + 1, visited, deadline);
      entries.add(new CapturedValue.MapEntry(key, value));
      count++;
    }

    return CapturedValue.ofMap(typeName, entries, map.size());
  }

  private static CapturedValue serializeObject(
      Object obj,
      CaptureConfiguration config,
      int objectDepth,
      int collectionDepth,
      IdentityHashMap<Object, Boolean> visited,
      long deadline) {

    String typeName = obj.getClass().getName();
    Map<String, CapturedValue> fields = new HashMap<>();

    Field[] declaredFields = obj.getClass().getDeclaredFields();
    int fieldCount = 0;
    int maxFields = config.getMaxFieldsPerObject();

    for (Field field : declaredFields) {
      if (System.nanoTime() > deadline) {
        return CapturedValue.notCaptured(typeName, CapturedValue.NotCapturedReason.TIMEOUT);
      }

      if (field.isSynthetic()) {
        continue;
      }

      if (fieldCount >= maxFields) {
        break;
      }

      String fieldName = field.getName();

      try {
        field.setAccessible(true);
        Object fieldValue = field.get(obj);
        CapturedValue capturedValue =
            serializeInternal(
                fieldValue,
                field.getType().getName(),
                config,
                objectDepth + 1,
                collectionDepth,
                visited,
                deadline);
        fields.put(fieldName, capturedValue);
        fieldCount++;
      } catch (Throwable e) {
        // Catch Throwable: field access via reflection can throw LinkageError,
        // StackOverflowError (deep recursion), or OutOfMemoryError. Since this runs
        // on the user's thread (via DIDataStore.captureLocals), we must suppress all errors.
        continue;
      }
    }

    return CapturedValue.ofObject(typeName, fields);
  }

  private static boolean isPrimitiveOrWrapper(Object value) {
    return value instanceof Number || value instanceof Boolean || value instanceof Character;
  }

  private static String getTypeName(Object value, String declaredType) {
    if (value != null) {
      return value.getClass().getName();
    }
    return declaredType != null ? declaredType : "unknown";
  }
}
