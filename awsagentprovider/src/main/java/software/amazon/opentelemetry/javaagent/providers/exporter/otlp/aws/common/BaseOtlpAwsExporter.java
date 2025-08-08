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

package software.amazon.opentelemetry.javaagent.providers.exporter.otlp.aws.common;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Abstract base class providing shared functionality for AWS (OTLP) exporters authenticated with
 * Sigv4.
 */
public abstract class BaseOtlpAwsExporter {

  protected final String awsRegion;
  protected final String endpoint;
  protected final AtomicReference<ByteArrayOutputStream> data;
  protected final Supplier<Map<String, String>> headerSupplier;
  protected final CompressionMethod compression;

  protected BaseOtlpAwsExporter(String endpoint, CompressionMethod compression) {
    this.endpoint = endpoint.toLowerCase();
    this.compression = compression;
    this.awsRegion = endpoint.split("\\.")[1];
    this.data = new AtomicReference<>();
    this.headerSupplier = new AwsAuthHeaderSupplier(this);
  }

  public abstract String serviceName();

  public CompressionMethod getCompression() {
    return this.compression;
  }
}
