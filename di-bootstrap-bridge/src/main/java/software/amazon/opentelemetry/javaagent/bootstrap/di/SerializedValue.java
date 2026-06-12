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

package software.amazon.opentelemetry.javaagent.bootstrap.di;

import java.util.List;
import java.util.Map;

/** Serialized representation of a captured value. */
public final class SerializedValue {
  private final String type;
  private final String value;
  private final Map<String, SerializedValue> fields;
  private final List<SerializedValue> elements;
  private final List<SerializedValue[]> entries;
  private final boolean isNull;
  private final String notCapturedReason;
  private final boolean truncated;
  private final Integer size;

  public SerializedValue(
      String type,
      String value,
      Map<String, SerializedValue> fields,
      List<SerializedValue> elements,
      List<SerializedValue[]> entries,
      boolean isNull,
      String notCapturedReason,
      boolean truncated,
      Integer size) {
    this.type = type;
    this.value = value;
    this.fields = fields;
    this.elements = elements;
    this.entries = entries;
    this.isNull = isNull;
    this.notCapturedReason = notCapturedReason;
    this.truncated = truncated;
    this.size = size;
  }

  public String getType() {
    return type;
  }

  public String getValue() {
    return value;
  }

  public Map<String, SerializedValue> getFields() {
    return fields;
  }

  public List<SerializedValue> getElements() {
    return elements;
  }

  public List<SerializedValue[]> getEntries() {
    return entries;
  }

  public boolean isNull() {
    return isNull;
  }

  public String getNotCapturedReason() {
    return notCapturedReason;
  }

  public boolean isTruncated() {
    return truncated;
  }

  public Integer getSize() {
    return size;
  }
}
