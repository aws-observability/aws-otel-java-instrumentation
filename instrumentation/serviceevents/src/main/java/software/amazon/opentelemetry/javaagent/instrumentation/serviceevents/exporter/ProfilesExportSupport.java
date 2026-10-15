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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.exporter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;

/**
 * Leaf helpers shared by the HTTP and gRPC profiles exporters — gzip compression and the
 * uncompressed payload-size predicate. Kept as pure static utilities (no logging, no state) so each
 * exporter keeps its own transport-specific control flow and WARN wording.
 */
final class ProfilesExportSupport {

  private ProfilesExportSupport() {}

  /** gzip-compress {@code input}. */
  static byte[] gzip(byte[] input) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(32, input.length / 2));
    try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
      gz.write(input);
    }
    return baos.toByteArray();
  }

  /**
   * Whether {@code payload} exceeds the (uncompressed) payload-size limit. Backends typically cap
   * the decompressed body, so this is measured on the serialized request, not the gzipped body. A
   * limit {@code <= 0} disables the guard.
   */
  static boolean exceedsPayloadLimit(byte[] payload, long maxPayloadBytes) {
    return maxPayloadBytes > 0 && payload.length > maxPayloadBytes;
  }
}
