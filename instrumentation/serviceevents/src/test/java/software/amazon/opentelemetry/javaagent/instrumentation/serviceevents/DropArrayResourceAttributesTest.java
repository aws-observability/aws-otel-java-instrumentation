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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class DropArrayResourceAttributesTest {

  private Resource invokeDropArrayResourceAttributes(Resource resource) throws Exception {
    Method method =
        ServiceEventsInstrumentation.class.getDeclaredMethod(
            "dropArrayResourceAttributes", Resource.class);
    method.setAccessible(true);
    return (Resource) method.invoke(null, resource);
  }

  @Test
  void dropsArrayAttributes() throws Exception {
    Resource resource =
        Resource.create(
            Attributes.builder()
                .put(AttributeKey.stringKey("service.name"), "my-service")
                .put(AttributeKey.longKey("process.pid"), 12345L)
                .put(
                    AttributeKey.stringArrayKey("process.command_args"),
                    Arrays.asList("/usr/bin/java", "-jar", "app.jar"))
                .build());

    Resource result = invokeDropArrayResourceAttributes(resource);

    // Primitive attributes preserved
    assertEquals("my-service", result.getAttributes().get(AttributeKey.stringKey("service.name")));
    assertEquals(12345L, result.getAttributes().get(AttributeKey.longKey("process.pid")));

    // Array attribute dropped
    assertNull(result.getAttributes().get(AttributeKey.stringArrayKey("process.command_args")));
    assertEquals(2, result.getAttributes().size());
  }

  @Test
  void preservesAllPrimitiveTypes() throws Exception {
    Resource resource =
        Resource.create(
            Attributes.builder()
                .put(AttributeKey.stringKey("str"), "value")
                .put(AttributeKey.longKey("lng"), 42L)
                .put(AttributeKey.doubleKey("dbl"), 3.14)
                .put(AttributeKey.booleanKey("bool"), true)
                .build());

    Resource result = invokeDropArrayResourceAttributes(resource);

    assertEquals(4, result.getAttributes().size());
    assertEquals("value", result.getAttributes().get(AttributeKey.stringKey("str")));
    assertEquals(42L, result.getAttributes().get(AttributeKey.longKey("lng")));
    assertEquals(3.14, result.getAttributes().get(AttributeKey.doubleKey("dbl")));
    assertEquals(true, result.getAttributes().get(AttributeKey.booleanKey("bool")));
  }

  @Test
  void dropsMultipleArrayAttributes() throws Exception {
    Resource resource =
        Resource.create(
            Attributes.builder()
                .put(AttributeKey.stringKey("service.name"), "svc")
                .put(
                    AttributeKey.stringArrayKey("process.command_args"),
                    Arrays.asList("java", "-jar"))
                .put(AttributeKey.stringArrayKey("custom.tags"), Arrays.asList("tag1", "tag2"))
                .build());

    Resource result = invokeDropArrayResourceAttributes(resource);

    assertEquals(1, result.getAttributes().size());
    assertEquals("svc", result.getAttributes().get(AttributeKey.stringKey("service.name")));
  }

  @Test
  void preservesSchemaUrl() throws Exception {
    Resource resource =
        Resource.create(
            Attributes.of(AttributeKey.stringKey("k"), "v"),
            "https://opentelemetry.io/schemas/1.24.0");

    Resource result = invokeDropArrayResourceAttributes(resource);

    assertEquals("https://opentelemetry.io/schemas/1.24.0", result.getSchemaUrl());
  }

  @Test
  void emptyResourceReturnsEmpty() throws Exception {
    Resource resource = Resource.create(Attributes.empty());

    Resource result = invokeDropArrayResourceAttributes(resource);

    assertEquals(0, result.getAttributes().size());
  }
}
