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

package software.amazon.opentelemetry.appsignals.test.images.mockcollector;

import com.linecorp.armeria.common.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Shared helpers for the mock OTLP HTTP collector endpoints.
 *
 * <p>Real OTel collectors honor {@code Content-Encoding: gzip} (and {@code deflate}) on incoming
 * OTLP requests. Without this decoder step the mock collector feeds the still-compressed bytes to
 * {@code ExportLogsServiceRequest.parseFrom}, which raises {@code InvalidProtocolBufferException}
 * and closes the connection — seen by the client as {@code RemoteDisconnected} / {@code
 * ClosedSessionException}. Mirrored from the fix applied to the Python mock collector.
 */
final class MockCollectorHttpUtil {

  private MockCollectorHttpUtil() {}

  static byte[] decodeIfCompressed(byte[] raw, @Nullable String contentEncoding)
      throws IOException {
    if (contentEncoding == null) {
      return raw;
    }
    String normalized = contentEncoding.trim().toLowerCase();
    if ("gzip".equals(normalized)) {
      try (var in = new GZIPInputStream(new java.io.ByteArrayInputStream(raw));
          var out = new ByteArrayOutputStream(raw.length * 2)) {
        in.transferTo(out);
        return out.toByteArray();
      }
    }
    if ("deflate".equals(normalized)) {
      try (var in = new InflaterInputStream(new java.io.ByteArrayInputStream(raw));
          var out = new ByteArrayOutputStream(raw.length * 2)) {
        in.transferTo(out);
        return out.toByteArray();
      }
    }
    return raw;
  }
}
