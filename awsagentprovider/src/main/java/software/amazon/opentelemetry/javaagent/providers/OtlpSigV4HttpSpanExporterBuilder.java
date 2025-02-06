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

package software.amazon.opentelemetry.javaagent.providers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class OtlpSigV4HttpSpanExporterBuilder {
  public static final long DEFAULT_TIMEOUT_SECS = 10;
  public static final long DEFAULT_CONNECT_TIMEOUT_SECS = 10;
  public static final String CLOUDWATCH_OTLP_TRACES_ENDPOINT =
      "https://xray.us-east-1.amazonaws.com/v1/traces";

  private long timeoutNanos = TimeUnit.SECONDS.toNanos(DEFAULT_TIMEOUT_SECS);
  private long connectTimeoutNanos = TimeUnit.SECONDS.toNanos(DEFAULT_CONNECT_TIMEOUT_SECS);

  private final Map<String, String> constantHeaders = new HashMap<>();
  private Supplier<Map<String, String>> headerSupplier = Collections::emptyMap;

  private OtlpSigV4HttpSender sender;

  public OtlpSigV4HttpSpanExporter build() {
    Supplier<Map<String, List<String>>> headerSupplier =
        () -> {
          Map<String, List<String>> result = new HashMap<>();
          Map<String, String> supplierResult = this.headerSupplier.get();
          if (supplierResult != null) {
            supplierResult.forEach(
                (key, value) -> result.put(key, Collections.singletonList(value)));
          }
          constantHeaders.forEach(
              (key, value) ->
                  result.merge(
                      key,
                      Collections.singletonList(value),
                      (v1, v2) -> {
                        List<String> merged = new ArrayList<>(v1);
                        merged.addAll(v2);
                        return merged;
                      }));
          return result;
        };

    if (sender == null) {
      this.sender =
          new OtlpSigV4HttpSender(
              CLOUDWATCH_OTLP_TRACES_ENDPOINT,
              timeoutNanos,
              connectTimeoutNanos,
              headerSupplier,
              false); // TODO: Fix
    }
    return new OtlpSigV4HttpSpanExporter(this.sender);
  }

  // Only for testing
  OtlpSigV4HttpSpanExporterBuilder setSender(OtlpSigV4HttpSender sender) {
    this.sender = sender;
    return this;
  }
}
