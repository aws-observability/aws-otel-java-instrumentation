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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.proto.collector.profiles.v1development.ExportProfilesServiceRequest;
import io.opentelemetry.proto.profiles.v1development.KeyValueAndUnit;
import io.opentelemetry.proto.profiles.v1development.Link;
import io.opentelemetry.proto.profiles.v1development.Profile;
import io.opentelemetry.proto.profiles.v1development.ProfilesDictionary;
import io.opentelemetry.proto.profiles.v1development.Sample;
import io.opentelemetry.sdk.resources.Resource;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.OtlpProfileBuilder;
import software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.utils.SpanMetadata;

/**
 * Fixture-backed scanner test for the <b>Span-API correlation path</b>.
 *
 * <p>The fixture {@code /span-wall-alloc-sample.jfr} is a real async-profiler 4.5 recording
 * produced with {@code one.profiler.Span.start()/end(tag)} calls (wall + alloc + {@code
 * profiler.Span} in one JFR session). It contains two {@code profiler.Span} events on the {@code
 * main} thread:
 *
 * <ul>
 *   <li>{@code "GET /api/orders"} — <b>sampled</b>, tag {@code op|traceIdHex|spanIdHex}
 *   <li>{@code "POST /api/checkout"} — <b>unsampled</b>, tag {@code op||} (operation only, no Link)
 * </ul>
 *
 * <p>This exercises the whole Span-API read path end to end: {@link
 * RotationBoundaryProcessor#indexSpansFromJfr} decodes the {@code profiler.Span} events into the
 * per-thread interval index, and {@link RotationBoundaryProcessor#scanJfrFileSinglePass} attaches
 * the decoded operation (and, only when sampled, the trace {@code Link}) to wall + alloc samples
 * via the per-thread {@code floorEntry} + strict-containment join.
 */
class RotationBoundaryProcessorSpanApiScanTest {

  private static final long WALL_PERIOD_NS = 10_000_000L; // 10ms
  private static final long ALLOC_PERIOD_BYTES = 524_288L;

  // These must match the tag encoding baked into the fixture.
  private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
  private static final String SPAN_ID = "b7ad6b7169203331";
  private static final String SAMPLED_OP = "GET /api/orders";
  private static final String UNSAMPLED_OP = "POST /api/checkout";

  private static Path fixture() throws Exception {
    return Path.of(
        RotationBoundaryProcessorSpanApiScanTest.class
            .getResource("/span-wall-alloc-sample.jfr")
            .toURI());
  }

  private static RotationBoundaryProcessor newProcessor() {
    return new RotationBoundaryProcessor(10000, null, 60, null, null, false, 0);
  }

  private static OtlpProfileBuilder newBuilder() {
    return new OtlpProfileBuilder(0L, 60_000_000_000L, WALL_PERIOD_NS, ALLOC_PERIOD_BYTES);
  }

  @Test
  void indexSpansFromJfr_decodesProfilerSpanEvents_intoPerThreadInterval() throws Exception {
    RotationBoundaryProcessor proc = newProcessor();
    Map<String, TreeMap<Long, SpanMetadata>> spanIndex = new HashMap<>();

    int indexed = proc.indexSpansFromJfr(fixture(), spanIndex);

    assertEquals(2, indexed, "fixture carries exactly two profiler.Span events");
    TreeMap<Long, SpanMetadata> mainSpans = spanIndex.get("main");
    assertNotNull(mainSpans, "both spans were recorded on the main thread");
    assertEquals(2, mainSpans.size());

    SpanMetadata sampled = findByOperation(mainSpans, SAMPLED_OP);
    assertNotNull(sampled, "sampled span must be indexed");
    assertEquals(TRACE_ID, sampled.traceId, "sampled span decodes its trace id");
    assertEquals(SPAN_ID, sampled.spanId, "sampled span decodes its span id");
    assertTrue(sampled.endNs > sampled.startNs, "interval derived from startTime + timeSpan");

    SpanMetadata unsampled = findByOperation(mainSpans, UNSAMPLED_OP);
    assertNotNull(unsampled, "unsampled span must be indexed");
    assertNull(unsampled.traceId, "unsampled span decodes to no trace id");
    assertNull(unsampled.spanId, "unsampled span decodes to no span id");
  }

  @Test
  void scan_attributesOperationAndLink_fromDecodedProfilerSpanTag() throws Exception {
    RotationBoundaryProcessor proc = newProcessor();
    OtlpProfileBuilder builder = newBuilder();

    // Build the correlation index straight from the profiler.Span events, then scan the samples —
    // exactly the two-step the live rotation pipeline runs.
    Map<String, TreeMap<Long, SpanMetadata>> spanIndex = new HashMap<>();
    proc.indexSpansFromJfr(fixture(), spanIndex);
    proc.scanJfrFileSinglePass(fixture(), spanIndex, builder, new HashMap<>());

    assertTrue(builder.getSampleCount() > 0, "fixture must yield wall samples");
    assertTrue(builder.getAllocSampleCount() > 0, "fixture must yield alloc samples");

    ExportProfilesServiceRequest request = builder.toExportRequest(Resource.getDefault());
    ProfilesDictionary dict = request.getDictionary();

    long sampledWithOp = 0;
    long sampledWithLink = 0;
    long unsampledWithOp = 0;
    long unsampledWithLink = 0;

    for (Profile p : allProfiles(request)) {
      for (Sample s : p.getSamplesList()) {
        String op = operationOf(s, dict);
        boolean hasLink = s.getLinkIndex() != 0;
        if (SAMPLED_OP.equals(op)) {
          sampledWithOp++;
          if (hasLink) {
            Link link = dict.getLinkTable(s.getLinkIndex());
            assertEquals(16, link.getTraceId().size(), "trace_id must be 16 bytes");
            assertEquals(8, link.getSpanId().size(), "span_id must be 8 bytes");
            assertEquals(TRACE_ID, toHex(link.getTraceId().toByteArray()));
            assertEquals(SPAN_ID, toHex(link.getSpanId().toByteArray()));
            sampledWithLink++;
          }
        } else if (UNSAMPLED_OP.equals(op)) {
          unsampledWithOp++;
          if (hasLink) {
            unsampledWithLink++;
          }
        }
      }
    }

    // Sampled span → operation attribute AND trace Link on its samples.
    assertTrue(sampledWithOp > 0, "sampled-span samples must carry the operation attribute");
    assertTrue(sampledWithLink > 0, "sampled-span samples must carry the trace Link");
    assertEquals(
        sampledWithOp,
        sampledWithLink,
        "every sampled-span sample carrying the operation must also carry the Link");

    // Unsampled span → operation only, never a Link.
    assertTrue(unsampledWithOp > 0, "unsampled-span samples must carry the operation attribute");
    assertEquals(0, unsampledWithLink, "unsampled-span samples must NOT carry a trace Link");
  }

  private static SpanMetadata findByOperation(TreeMap<Long, SpanMetadata> spans, String op) {
    for (SpanMetadata s : spans.values()) {
      if (op.equals(s.operation)) {
        return s;
      }
    }
    return null;
  }

  private static java.util.List<Profile> allProfiles(ExportProfilesServiceRequest req) {
    java.util.List<Profile> out = new java.util.ArrayList<>();
    req.getResourceProfilesList()
        .forEach(rp -> rp.getScopeProfilesList().forEach(sp -> out.addAll(sp.getProfilesList())));
    return out;
  }

  private static String operationOf(Sample s, ProfilesDictionary dict) {
    for (int idx : s.getAttributeIndicesList()) {
      KeyValueAndUnit attr = dict.getAttributeTable(idx);
      if ("operation".equals(dict.getStringTable(attr.getKeyStrindex()))) {
        return attr.getValue().getStringValue();
      }
    }
    return null;
  }

  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }
}
