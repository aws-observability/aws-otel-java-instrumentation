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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.output;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import software.amazon.opentelemetry.javaagent.bootstrap.di.DISerializer;
import software.amazon.opentelemetry.javaagent.bootstrap.di.SerializedValue;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.instrumentation.util.ReflectiveValueSerializer;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.CaptureConfiguration;
import software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.snapshot.CapturedValue;

/** Implementation of DISerializer using ReflectiveValueSerializer. */
public final class DISerializerImpl implements DISerializer {

  @Override
  public Map<String, SerializedValue> serialize(
      Map<String, Object> values,
      int maxDepth,
      int maxFields,
      int maxCollWidth,
      int maxCollDepth,
      int maxStrLen,
      long timeoutMs) {

    if (values == null || values.isEmpty()) {
      return new HashMap<>();
    }

    CaptureConfiguration config =
        CaptureConfiguration.builder()
            .maxObjectDepth(maxDepth)
            .maxFieldsPerObject(maxFields)
            .maxCollectionWidth(maxCollWidth)
            .maxCollectionDepth(maxCollDepth)
            .maxStringLength(maxStrLen)
            .build();

    Map<String, SerializedValue> result = new HashMap<>();
    long startTime = System.currentTimeMillis();

    for (Map.Entry<String, Object> entry : values.entrySet()) {
      if (System.currentTimeMillis() - startTime > timeoutMs) {
        break;
      }

      CapturedValue captured = ReflectiveValueSerializer.serialize(entry.getValue(), config);
      result.put(entry.getKey(), toSerializedValue(captured));
    }

    return result;
  }

  private static SerializedValue toSerializedValue(CapturedValue cv) {
    if (cv == null) {
      return new SerializedValue("unknown", null, null, null, null, true, null, false, null);
    }

    String notCapturedReason =
        cv.getNotCapturedReason() != null ? cv.getNotCapturedReason().name() : null;

    Map<String, SerializedValue> fields = null;
    if (cv.getFields() != null) {
      fields = new HashMap<>();
      for (Map.Entry<String, CapturedValue> entry : cv.getFields().entrySet()) {
        fields.put(entry.getKey(), toSerializedValue(entry.getValue()));
      }
    }

    List<SerializedValue> elements = null;
    if (cv.getElements() != null) {
      elements = new ArrayList<>();
      for (CapturedValue element : cv.getElements()) {
        elements.add(toSerializedValue(element));
      }
    }

    List<SerializedValue[]> entries = null;
    if (cv.getEntries() != null) {
      entries = new ArrayList<>();
      for (CapturedValue.MapEntry entry : cv.getEntries()) {
        entries.add(
            new SerializedValue[] {
              toSerializedValue(entry.getKey()), toSerializedValue(entry.getValue())
            });
      }
    }

    return new SerializedValue(
        cv.getType(),
        cv.getValue(),
        fields,
        elements,
        entries,
        cv.isNull(),
        notCapturedReason,
        cv.isTruncated(),
        cv.getSize());
  }
}
