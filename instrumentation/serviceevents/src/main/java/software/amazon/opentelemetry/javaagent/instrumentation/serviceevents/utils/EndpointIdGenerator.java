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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Utility class for generating deterministic endpoint IDs.
 *
 * <p>Uses UUID5 (name-based UUID using SHA-1) to create consistent, deterministic hashes for
 * endpoint identification.
 */
public class EndpointIdGenerator {

  // UUID namespace for deterministic endpoint_id generation
  // Using DNS namespace as base for deterministic endpoint identification
  private static final UUID NAMESPACE_DNS = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
  private static final UUID ENDPOINT_UUID_NAMESPACE;

  static {
    // Create namespace: uuid5(NAMESPACE_DNS, "serviceevents.endpoint")
    ENDPOINT_UUID_NAMESPACE = uuid5(NAMESPACE_DNS, "serviceevents.endpoint");
  }

  private EndpointIdGenerator() {
    // Utility class
  }

  /**
   * Generate a deterministic endpoint_id hash for a route+method combination.
   *
   * <p>Uses UUID5 to create a consistent, deterministic hash for endpoint identification. The
   * endpoint_id is deterministic - same route+method always produces the same UUID.
   *
   * @param route Route pattern (e.g., "/users/{id}")
   * @param method HTTP method (e.g., "GET")
   * @return UUID5 string (e.g., "80596d8d-98e5-5f3b-829c-77c9259bae17")
   */
  public static String generateEndpointId(String route, String method) {
    // Create deterministic name from method and route
    // Format: "METHOD:ROUTE" (e.g., "GET:/api/users")
    String endpointName = method + ":" + route;

    // Generate UUID5 hash using endpoint namespace
    UUID endpointUuid = uuid5(ENDPOINT_UUID_NAMESPACE, endpointName);

    return endpointUuid.toString();
  }

  /**
   * Generate UUID5 (name-based UUID using SHA-1).
   *
   * @param namespace Namespace UUID
   * @param name Name to hash
   * @return UUID5 based on namespace and name
   */
  public static UUID uuid5(UUID namespace, String name) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-1");

      // Add namespace bytes
      md.update(toBytes(namespace));

      // Add name bytes
      md.update(name.getBytes(StandardCharsets.UTF_8));

      byte[] sha1Bytes = md.digest();

      // Set version (5) and variant bits
      sha1Bytes[6] &= 0x0f; // Clear version bits
      sha1Bytes[6] |= 0x50; // Set version to 5
      sha1Bytes[8] &= 0x3f; // Clear variant bits
      sha1Bytes[8] |= 0x80; // Set variant to RFC 4122

      return fromBytes(sha1Bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-1 algorithm not available", e);
    }
  }

  private static byte[] toBytes(UUID uuid) {
    byte[] bytes = new byte[16];
    long msb = uuid.getMostSignificantBits();
    long lsb = uuid.getLeastSignificantBits();

    for (int i = 0; i < 8; i++) {
      bytes[i] = (byte) ((msb >> (8 * (7 - i))) & 0xff);
    }
    for (int i = 8; i < 16; i++) {
      bytes[i] = (byte) ((lsb >> (8 * (15 - i))) & 0xff);
    }

    return bytes;
  }

  private static UUID fromBytes(byte[] bytes) {
    long msb = 0;
    long lsb = 0;

    for (int i = 0; i < 8; i++) {
      msb = (msb << 8) | (bytes[i] & 0xff);
    }
    for (int i = 8; i < 16; i++) {
      lsb = (lsb << 8) | (bytes[i] & 0xff);
    }

    return new UUID(msb, lsb);
  }
}
