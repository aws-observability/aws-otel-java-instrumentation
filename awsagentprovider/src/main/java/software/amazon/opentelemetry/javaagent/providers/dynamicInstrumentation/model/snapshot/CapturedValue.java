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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.snapshot;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Recursive, type-preserving value representation for captured data in dynamic instrumentation. */
public final class CapturedValue {

  public enum NotCapturedReason {
    DEPTH,
    FIELD_COUNT,
    COLLECTION_SIZE,
    TIMEOUT,
    ALREADY_CAPTURED
  }

  private final String type;
  private final String value;
  private final boolean truncated;
  private final Integer size;
  private final Map<String, CapturedValue> fields;
  private final List<CapturedValue> elements;
  private final List<MapEntry> entries;
  private final boolean isNull;
  private final NotCapturedReason notCapturedReason;

  private CapturedValue(
      String type,
      String value,
      boolean truncated,
      Integer size,
      Map<String, CapturedValue> fields,
      List<CapturedValue> elements,
      List<MapEntry> entries,
      boolean isNull,
      NotCapturedReason notCapturedReason) {
    this.type = type;
    this.value = value;
    this.truncated = truncated;
    this.size = size;
    this.fields = fields;
    this.elements = elements;
    this.entries = entries;
    this.isNull = isNull;
    this.notCapturedReason = notCapturedReason;
  }

  public static CapturedValue ofPrimitive(String type, String value) {
    return new CapturedValue(type, value, false, null, null, null, null, false, null);
  }

  public static CapturedValue ofString(String value, boolean truncated, int size) {
    return new CapturedValue(
        "java.lang.String", value, truncated, size, null, null, null, false, null);
  }

  public static CapturedValue ofObject(String type, Map<String, CapturedValue> fields) {
    return new CapturedValue(
        type, null, false, null, Collections.unmodifiableMap(fields), null, null, false, null);
  }

  public static CapturedValue ofCollection(String type, List<CapturedValue> elements, int size) {
    return new CapturedValue(
        type, null, false, size, null, Collections.unmodifiableList(elements), null, false, null);
  }

  public static CapturedValue ofMap(String type, List<MapEntry> entries, int size) {
    return new CapturedValue(
        type, null, false, size, null, null, Collections.unmodifiableList(entries), false, null);
  }

  public static CapturedValue ofNull(String type) {
    return new CapturedValue(type, null, false, null, null, null, null, true, null);
  }

  public static CapturedValue notCaptured(String type, NotCapturedReason reason) {
    return new CapturedValue(type, null, false, null, null, null, null, false, reason);
  }

  public String getType() {
    return type;
  }

  public String getValue() {
    return value;
  }

  public boolean isTruncated() {
    return truncated;
  }

  public Integer getSize() {
    return size;
  }

  public Map<String, CapturedValue> getFields() {
    return fields;
  }

  public List<CapturedValue> getElements() {
    return elements;
  }

  public List<MapEntry> getEntries() {
    return entries;
  }

  public boolean isNull() {
    return isNull;
  }

  public NotCapturedReason getNotCapturedReason() {
    return notCapturedReason;
  }

  public static final class MapEntry {
    private final CapturedValue key;
    private final CapturedValue value;

    public MapEntry(CapturedValue key, CapturedValue value) {
      this.key = key;
      this.value = value;
    }

    public CapturedValue getKey() {
      return key;
    }

    public CapturedValue getValue() {
      return value;
    }
  }
}
