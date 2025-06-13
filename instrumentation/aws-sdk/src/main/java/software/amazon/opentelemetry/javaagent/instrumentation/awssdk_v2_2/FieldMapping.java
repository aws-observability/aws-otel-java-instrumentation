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

package software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

class FieldMapping {

  enum Type {
    REQUEST,
    RESPONSE
  }

  private final Type type;
  private final String attribute;
  private final List<String> fields;

  static FieldMapping request(String attribute, String fieldPath) {
    return new FieldMapping(Type.REQUEST, attribute, fieldPath);
  }

  static FieldMapping response(String attribute, String fieldPath) {
    return new FieldMapping(Type.RESPONSE, attribute, fieldPath);
  }

  FieldMapping(Type type, String attribute, String fieldPath) {
    this.type = type;
    this.attribute = attribute;
    this.fields = Collections.unmodifiableList(Arrays.asList(fieldPath.split("\\.")));
  }

  String getAttribute() {
    return attribute;
  }

  List<String> getFields() {
    return fields;
  }

  Type getType() {
    return type;
  }

  static Map<Type, List<FieldMapping>> groupByType(FieldMapping[] fieldMappings) {

    EnumMap<Type, List<FieldMapping>> fields = new EnumMap<>(Type.class);
    for (FieldMapping.Type type : FieldMapping.Type.values()) {
      fields.put(type, new ArrayList<>());
    }
    for (FieldMapping fieldMapping : fieldMappings) {
      fields.get(fieldMapping.getType()).add(fieldMapping);
    }
    return fields;
  }
}
