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
 * <p>Uses a name-based UUID scheme (similar to UUID5 but using SHA-256 instead of SHA-1) to create
 * consistent, deterministic hashes for endpoint identification. SHA-256 is used for FIPS 140-2/3
 * compliance, as SHA-1 is not an approved algorithm in FIPS-enabled environments.
 *
 * <p>Note: This produces different IDs than RFC 4122 UUID5 (which mandates SHA-1). The version
 * nibble is set to 8 (custom) per RFC 9562 to distinguish from standard UUID5.
 */
public class EndpointIdGenerator {

  private static final String HASH_ALGORITHM = "SHA-256";

  // UUID namespace for deterministic endpoint_id generation
  // Using DNS namespace as base for deterministic endpoint identification
  private static final UUID NAMESPACE_DNS = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
  private static final UUID ENDPOINT_UUID_NAMESPACE;

  static {
    // Create namespace: nameBasedUuid(NAMESPACE_DNS, "serviceevents.endpoint")
    ENDPOINT_UUID_NAMESPACE = nameBasedUuid(NAMESPACE_DNS, "serviceevents.endpoint");
  }

  private EndpointIdGenerator() {
    // Utility class
  }

  /**
   * Generate a deterministic endpoint_id hash for a route+method combination.
   *
   * <p>Uses a SHA-256-based name UUID to create a consistent, deterministic hash for endpoint
   * identification. The endpoint_id is deterministic - same route+method always produces the same
   * UUID.
   *
   * @param route Route pattern (e.g., "/users/{id}")
   * @param method HTTP method (e.g., "GET")
   * @return Name-based UUID string (e.g., "80596d8d-98e5-8f3b-829c-77c9259bae17")
   */
  public static String generateEndpointId(String route, String method) {
    // Create deterministic name from method and route
    // Format: "METHOD:ROUTE" (e.g., "GET:/api/users")
    String endpointName = method + ":" + route;

    // Generate name-based UUID hash using endpoint namespace
    UUID endpointUuid = nameBasedUuid(ENDPOINT_UUID_NAMESPACE, endpointName);

    return endpointUuid.toString();
  }

  /**
   * Generate a deterministic name-based UUID using SHA-256.
   *
   * <p>This follows the same structure as UUID5 (RFC 4122) but substitutes SHA-256 for SHA-1 to
   * ensure FIPS 140-2/3 compliance. The version nibble is set to 8 (custom/experimental per RFC
   * 9562) to clearly distinguish these from standard UUID5 values.
   *
   * @param namespace Namespace UUID
   * @param name Name to hash
   * @return Deterministic UUID based on namespace and name
   */
  public static UUID nameBasedUuid(UUID namespace, String name) {
    try {
      MessageDigest md = MessageDigest.getInstance(HASH_ALGORITHM);

      // Add namespace bytes
      md.update(toBytes(namespace));

      // Add name bytes
      md.update(name.getBytes(StandardCharsets.UTF_8));

      byte[] hashBytes = md.digest();

      // Truncate SHA-256 (32 bytes) to 16 bytes for UUID
      // Set version (8 = custom per RFC 9562) and variant bits
      hashBytes[6] &= 0x0f; // Clear version bits
      hashBytes[6] |= (byte) 0x80; // Set version to 8 (custom)
      hashBytes[8] &= 0x3f; // Clear variant bits
      hashBytes[8] |= (byte) 0x80; // Set variant to RFC 4122

      return fromBytes(hashBytes);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(HASH_ALGORITHM + " algorithm not available", e);
    }
  }

  /**
   * Generate UUID5 (name-based UUID using SHA-1).
   *
   * @deprecated Use {@link #nameBasedUuid(UUID, String)} instead for FIPS compliance. This method
   *     is retained only for backward compatibility in non-FIPS environments.
   * @param namespace Namespace UUID
   * @param name Name to hash
   * @return UUID5 based on namespace and name
   */
  @Deprecated
  public static UUID uuid5(UUID namespace, String name) {
    return nameBasedUuid(namespace, name);
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
