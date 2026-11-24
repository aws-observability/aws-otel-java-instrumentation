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

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.CaptureConfiguration;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.snapshot.CapturedValue;

class ReflectiveValueSerializerLimitsTest {

  @Test
  void maxStringLengthTruncatesStrings() {
    CaptureConfiguration config = CaptureConfiguration.builder().maxStringLength(5).build();
    CapturedValue result = ReflectiveValueSerializer.serialize("hello world", config);
    assertEquals("hello", result.getValue());
    assertTrue(result.isTruncated());
    assertEquals(11, result.getSize());
  }

  @Test
  void maxCollectionWidthLimitsElements() {
    List<String> list = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      list.add("item" + i);
    }
    CaptureConfiguration config = CaptureConfiguration.builder().maxCollectionWidth(3).build();
    CapturedValue result = ReflectiveValueSerializer.serialize(list, config);
    assertEquals(3, result.getElements().size());
    assertEquals(50, result.getSize());
  }

  @Test
  void maxObjectDepthStopsFieldTraversal() {
    // Depth 0: outer, Depth 1: inner field
    CaptureConfiguration config = CaptureConfiguration.builder().maxObjectDepth(1).build();
    Outer outer = new Outer();
    outer.inner = new Inner();
    outer.inner.value = "deep";

    CapturedValue result = ReflectiveValueSerializer.serialize(outer, config);
    assertNotNull(result.getFields());
    CapturedValue innerField = result.getFields().get("inner");
    // inner is at objectDepth=1 which equals max, so should be notCaptured
    assertEquals("DEPTH", innerField.getNotCapturedReason().name());
  }

  @Test
  void maxCollectionDepthStopsNestedCollections() {
    // List<List<List<String>>> — 3 levels of nesting
    List<List<List<String>>> nested = new ArrayList<>();
    List<List<String>> mid = new ArrayList<>();
    List<String> inner = new ArrayList<>();
    inner.add("value");
    mid.add(inner);
    nested.add(mid);

    CaptureConfiguration config =
        CaptureConfiguration.builder().maxCollectionDepth(2).maxObjectDepth(5).build();
    CapturedValue result = ReflectiveValueSerializer.serialize(nested, config);

    // Level 0: nested list itself (collDepth=0, serialized)
    assertNotNull(result.getElements());
    // Level 1: mid list (collDepth=1, serialized)
    CapturedValue midResult = result.getElements().get(0);
    assertNotNull(midResult.getElements());
    // Level 2: inner list (collDepth=2, should be DEPTH-limited)
    CapturedValue innerResult = midResult.getElements().get(0);
    assertEquals("DEPTH", innerResult.getNotCapturedReason().name());
  }

  @Test
  void collectionDepthAndObjectDepthAreIndependent() {
    // Object containing a list — objectDepth increments for the object,
    // collectionDepth increments for the list
    CaptureConfiguration config =
        CaptureConfiguration.builder().maxObjectDepth(2).maxCollectionDepth(1).build();

    ObjectWithList obj = new ObjectWithList();
    obj.items = new ArrayList<>();
    obj.items.add("a");

    CapturedValue result = ReflectiveValueSerializer.serialize(obj, config);
    // Object at objectDepth=0, fields at objectDepth=1 — within limit
    assertNotNull(result.getFields());
    CapturedValue itemsField = result.getFields().get("items");
    // List at collectionDepth=0, elements at collectionDepth=1 — at limit
    assertNotNull(itemsField.getElements());
    assertEquals("a", itemsField.getElements().get(0).getValue());
  }

  @Test
  void maxFieldsPerObjectLimitsFields() {
    CaptureConfiguration config = CaptureConfiguration.builder().maxFieldsPerObject(1).build();
    ManyFields obj = new ManyFields();
    CapturedValue result = ReflectiveValueSerializer.serialize(obj, config);
    assertEquals(1, result.getFields().size());
  }

  @Test
  void mapRespectsCollectionDepth() {
    Map<String, Map<String, String>> nested = new HashMap<>();
    Map<String, String> inner = new HashMap<>();
    inner.put("k", "v");
    nested.put("outer", inner);

    CaptureConfiguration config = CaptureConfiguration.builder().maxCollectionDepth(1).build();
    CapturedValue result = ReflectiveValueSerializer.serialize(nested, config);
    // Map at collDepth=0, entries at collDepth=1 — at limit
    assertNotNull(result.getEntries());
    CapturedValue entryValue = result.getEntries().get(0).getValue();
    // Inner map at collDepth=1 which equals max, should be DEPTH
    assertEquals("DEPTH", entryValue.getNotCapturedReason().name());
  }

  @Test
  void fieldAccessThrowingErrorDoesNotCrash() {
    // Verifies that an object containing a field that throws a real Error during
    // Field.get() is handled gracefully without propagating to the caller.
    // This directly exercises the catch(Throwable) path in serializeObject().
    CaptureConfiguration config = CaptureConfiguration.builder().build();
    FieldGetThrowsError obj = new FieldGetThrowsError();

    // This must not throw — the serializer should catch the Error and continue
    CapturedValue result = ReflectiveValueSerializer.serialize(obj, config);

    assertNotNull(result);
    assertNotNull(result.getFields());
    // The "safeField" should still be captured despite "errorField" throwing during get()
    CapturedValue safeField = result.getFields().get("safeField");
    assertNotNull(safeField);
    assertEquals("safe", safeField.getValue());
  }

  @Test
  void collectionContainingProblematicObjectDoesNotCrash() {
    // Verifies that a collection containing objects that are difficult to serialize
    // (self-referential) is handled gracefully via depth/visited limits.
    CaptureConfiguration config = CaptureConfiguration.builder().maxObjectDepth(2).build();
    ObjectWithErrorField obj = new ObjectWithErrorField();
    obj.safe = "safe_value";
    // Self-referential object — will hit depth limit
    obj.dangerous = obj;

    // This should not throw — serializer handles cycles via visited set
    CapturedValue result = ReflectiveValueSerializer.serialize(obj, config);
    assertNotNull(result);
    assertNotNull(result.getFields());
    CapturedValue safeField = result.getFields().get("safe");
    assertNotNull(safeField);
    assertEquals("safe_value", safeField.getValue());
  }

  // Test helper classes

  static class Outer {
    Inner inner;
  }

  static class Inner {
    String value;
  }

  static class ObjectWithList {
    List<String> items;
  }

  static class ManyFields {
    String a = "1";
    String b = "2";
    String c = "3";
  }

  static class ObjectWithErrorField {
    String safe;
    Object dangerous;
  }

  /**
   * Object whose {@code errorField} causes the serializer to hit a real {@link Throwable} while
   * reflectively walking fields. The field holds a JDK type ({@link java.time.Duration}) with a
   * strongly-encapsulated internal field; when the serializer recurses into it and calls {@code
   * setAccessible(true)} on that field, the JVM (Java 9+) throws {@link
   * java.lang.reflect.InaccessibleObjectException}. This deterministically exercises the {@code
   * catch(Throwable)} path in {@code ReflectiveValueSerializer.serializeObject()} without depending
   * on stack depth (the serializer caps object depth, so a deep chain could never overflow it).
   *
   * <p>The fixture itself constructs cheaply, so it is safe on runners with a small default stack.
   */
  static class FieldGetThrowsError {
    @SuppressWarnings("unused")
    String safeField = "safe";

    @SuppressWarnings("unused")
    java.time.Duration errorField = java.time.Duration.ofSeconds(5);
  }
}
