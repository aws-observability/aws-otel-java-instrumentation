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

/**
 * A best-effort exporter for serialized OTLP {@code ExportProfilesServiceRequest} bytes.
 * Implemented by both transports — {@link OtlpHttpProfilesExporter} (OTLP/HTTP protobuf) and {@link
 * OtlpGrpcProfilesExporter} (OTLP/gRPC) — so the rotation pipeline holds one type and the transport
 * is chosen once at startup from {@code OTEL_EXPORTER_OTLP_PROTOCOL}.
 */
public interface ProfilesExporter {

  /**
   * Send the serialized {@code ExportProfilesServiceRequest} bytes. Best-effort; failures are
   * logged and swallowed so the profiler never disrupts the application.
   *
   * @return {@code true} if the backend accepted the request, {@code false} otherwise
   */
  boolean export(byte[] payload);

  /** The resolved endpoint this exporter sends to. */
  String getEndpoint();

  /** Release transport resources. Safe to call more than once. */
  void shutdown();
}
