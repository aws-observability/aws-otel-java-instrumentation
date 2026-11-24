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

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class LatencyThresholdResolverTest {

  @Test
  void exactMatch_returnsConfiguredThreshold() {
    LatencyThresholdResolver r =
        new LatencyThresholdResolver(Collections.singletonList("POST /api/checkout:500"));
    assertEquals(500.0, r.resolveThresholdMs("POST", "/api/checkout"));
    assertTrue(Double.isNaN(r.resolveThresholdMs("GET", "/api/checkout")));
  }

  @Test
  void routeGlob_matchesAnyPath() {
    LatencyThresholdResolver r =
        new LatencyThresholdResolver(Collections.singletonList("GET /api/*:1000"));
    assertEquals(1000.0, r.resolveThresholdMs("GET", "/api/foo"));
    assertEquals(1000.0, r.resolveThresholdMs("GET", "/api/bar/baz"));
    assertTrue(Double.isNaN(r.resolveThresholdMs("POST", "/api/foo")));
  }

  @Test
  void methodGlob_matchesAnyMethod() {
    LatencyThresholdResolver r =
        new LatencyThresholdResolver(Collections.singletonList("* /health:50"));
    assertEquals(50.0, r.resolveThresholdMs("GET", "/health"));
    assertEquals(50.0, r.resolveThresholdMs("POST", "/health"));
    assertEquals(50.0, r.resolveThresholdMs("PATCH", "/health"));
  }

  @Test
  void catchAll() {
    LatencyThresholdResolver r = new LatencyThresholdResolver(Collections.singletonList("* *:200"));
    assertEquals(200.0, r.resolveThresholdMs("GET", "/foo"));
    assertEquals(200.0, r.resolveThresholdMs("POST", "/a/b/c"));
  }

  @Test
  void firstMatchWins() {
    LatencyThresholdResolver r =
        new LatencyThresholdResolver(Arrays.asList("POST /api/*:500", "* *:200"));
    assertEquals(500.0, r.resolveThresholdMs("POST", "/api/foo"));
    assertEquals(200.0, r.resolveThresholdMs("GET", "/api/foo"));
    assertEquals(200.0, r.resolveThresholdMs("GET", "/random"));
  }

  @Test
  void routeWithComma_parsesCorrectly() {
    // The resolver receives already-split entries, so it parses a comma-bearing route fine on its
    // own. Note that ServiceEventsConfig now splits the env var on commas, so such an entry can no
    // longer be produced verbatim from configuration — a comma route must be matched with a glob
    // (e.g. "GET /search*:750"). This test pins the resolver's own behavior in isolation.
    LatencyThresholdResolver r =
        new LatencyThresholdResolver(Collections.singletonList("GET /search?q=a,b,c:750"));
    assertEquals(750.0, r.resolveThresholdMs("GET", "/search?q=a,b,c"));
  }

  @Test
  void routeWithColon_thresholdParsedFromLastColon() {
    // Path containing colons should still parse; threshold taken from after the LAST colon.
    LatencyThresholdResolver r =
        new LatencyThresholdResolver(Collections.singletonList("GET /a:b:c:123:900"));
    assertEquals(900.0, r.resolveThresholdMs("GET", "/a:b:c:123"));
  }

  @Test
  void methodIsCaseInsensitive() {
    LatencyThresholdResolver r =
        new LatencyThresholdResolver(Collections.singletonList("POST /api/checkout:500"));
    assertEquals(500.0, r.resolveThresholdMs("post", "/api/checkout"));
    assertEquals(500.0, r.resolveThresholdMs("Post", "/api/checkout"));
  }

  @Test
  void nullMethodOrRoute_returnsNaN() {
    LatencyThresholdResolver r = new LatencyThresholdResolver(Collections.singletonList("* *:200"));
    assertTrue(Double.isNaN(r.resolveThresholdMs(null, "/x")));
    assertTrue(Double.isNaN(r.resolveThresholdMs("GET", null)));
  }

  @Test
  void whitespace_trimmed() {
    LatencyThresholdResolver r =
        new LatencyThresholdResolver(
            Arrays.asList("  POST /api/checkout:500  ", "  GET /api/health:50  "));
    assertEquals(500.0, r.resolveThresholdMs("POST", "/api/checkout"));
    assertEquals(50.0, r.resolveThresholdMs("GET", "/api/health"));
    assertEquals(2, r.size());
  }

  @Test
  void malformed_missingColon_skippedWithoutThrowing() {
    LatencyThresholdResolver r =
        new LatencyThresholdResolver(Arrays.asList("malformed-no-colon", "GET /ok:200"));
    assertEquals(1, r.size());
    assertEquals(200.0, r.resolveThresholdMs("GET", "/ok"));
  }

  @Test
  void malformed_nonNumericThreshold_skippedWithoutThrowing() {
    LatencyThresholdResolver r =
        new LatencyThresholdResolver(Arrays.asList("GET /bad:notanumber", "GET /good:300"));
    assertEquals(1, r.size());
    assertEquals(300.0, r.resolveThresholdMs("GET", "/good"));
  }

  @Test
  void malformed_nonPositiveThreshold_skipped() {
    LatencyThresholdResolver r =
        new LatencyThresholdResolver(Arrays.asList("GET /zero:0", "GET /neg:-5", "GET /good:400"));
    assertEquals(1, r.size());
    assertEquals(400.0, r.resolveThresholdMs("GET", "/good"));
    assertTrue(Double.isNaN(r.resolveThresholdMs("GET", "/zero")));
    assertTrue(Double.isNaN(r.resolveThresholdMs("GET", "/neg")));
  }

  @Test
  void malformed_emptyPattern_skipped() {
    LatencyThresholdResolver r = new LatencyThresholdResolver(Arrays.asList(":500", "GET /ok:200"));
    assertEquals(1, r.size());
    assertEquals(200.0, r.resolveThresholdMs("GET", "/ok"));
  }

  @Test
  void emptyInput_emptyResolver() {
    LatencyThresholdResolver r = new LatencyThresholdResolver(Collections.emptyList());
    assertEquals(0, r.size());
    assertTrue(Double.isNaN(r.resolveThresholdMs("GET", "/anything")));
  }

  @Test
  void nullInput_emptyResolver() {
    LatencyThresholdResolver r = new LatencyThresholdResolver(null);
    assertEquals(0, r.size());
    assertTrue(Double.isNaN(r.resolveThresholdMs("GET", "/anything")));
  }

  @Test
  void globToRegex_escapesRegexMetacharacters() {
    // Paths with regex-special chars should be matched literally, not interpreted.
    LatencyThresholdResolver r =
        new LatencyThresholdResolver(Collections.singletonList("GET /a.b+c:700"));
    assertEquals(700.0, r.resolveThresholdMs("GET", "/a.b+c"));
    // '.' in the pattern should NOT match any char — only literal '.'.
    assertTrue(Double.isNaN(r.resolveThresholdMs("GET", "/aXb+c")));
  }
}
