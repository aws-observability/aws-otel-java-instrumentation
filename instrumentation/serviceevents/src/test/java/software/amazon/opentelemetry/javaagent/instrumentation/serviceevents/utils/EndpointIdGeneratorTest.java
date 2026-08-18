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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class EndpointIdGeneratorTest {

  @Test
  void generateEndpointId_isDeterministic() {
    String id1 = EndpointIdGenerator.generateEndpointId("/api/users", "GET");
    String id2 = EndpointIdGenerator.generateEndpointId("/api/users", "GET");
    assertEquals(id1, id2, "Same route+method should produce the same ID");
  }

  @Test
  void generateEndpointId_differentInputsProduceDifferentIds() {
    String getId = EndpointIdGenerator.generateEndpointId("/api/users", "GET");
    String postId = EndpointIdGenerator.generateEndpointId("/api/users", "POST");
    String otherId = EndpointIdGenerator.generateEndpointId("/api/orders", "GET");

    assertNotEquals(getId, postId, "Different methods should produce different IDs");
    assertNotEquals(getId, otherId, "Different routes should produce different IDs");
  }

  @Test
  void generateEndpointId_returnsValidUuidFormat() {
    String id = EndpointIdGenerator.generateEndpointId("/api/users", "GET");
    // Should not throw
    UUID parsed = UUID.fromString(id);
    assertNotNull(parsed);
  }

  @Test
  void generateEndpointId_usesVersion8() {
    String id = EndpointIdGenerator.generateEndpointId("/api/users", "GET");
    UUID parsed = UUID.fromString(id);
    assertEquals(8, parsed.version(), "Should use UUID version 8 (custom per RFC 9562)");
  }

  @Test
  void nameBasedUuid_isDeterministic() {
    UUID ns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
    UUID id1 = EndpointIdGenerator.nameBasedUuid(ns, "test");
    UUID id2 = EndpointIdGenerator.nameBasedUuid(ns, "test");
    assertEquals(id1, id2, "Same namespace+name should produce the same UUID");
  }

  @Test
  void nameBasedUuid_setsCorrectVersionAndVariant() {
    UUID ns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
    UUID result = EndpointIdGenerator.nameBasedUuid(ns, "test");
    assertEquals(8, result.version(), "Version nibble should be 8");
    assertEquals(2, result.variant(), "Variant should be RFC 4122 (2)");
  }

  @SuppressWarnings("deprecation")
  @Test
  void uuid5_delegatesToNameBasedUuid() {
    UUID ns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
    UUID fromDeprecated = EndpointIdGenerator.uuid5(ns, "test");
    UUID fromNew = EndpointIdGenerator.nameBasedUuid(ns, "test");
    assertEquals(fromDeprecated, fromNew, "Deprecated uuid5() should delegate to nameBasedUuid()");
  }
}
