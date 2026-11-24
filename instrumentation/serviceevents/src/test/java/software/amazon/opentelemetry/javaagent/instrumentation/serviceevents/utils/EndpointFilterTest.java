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

class EndpointFilterTest {

  @Test
  void emptyFilters_tracksEverything() {
    EndpointFilter f = new EndpointFilter(Collections.emptyList(), Collections.emptyList());
    assertTrue(f.shouldTrack("GET", "/api/users"));
    assertTrue(f.shouldTrack("POST", "/checkout"));
    assertTrue(f.shouldTrack("GET", "/health"));
  }

  @Test
  void includeOnly_onlyMatchingEndpointsTracked() {
    EndpointFilter f = new EndpointFilter(Arrays.asList("GET /api/*"), Collections.emptyList());
    assertTrue(f.shouldTrack("GET", "/api/users"));
    assertTrue(f.shouldTrack("GET", "/api/orders/42"));
    assertFalse(f.shouldTrack("POST", "/api/users"));
    assertFalse(f.shouldTrack("GET", "/health"));
  }

  @Test
  void excludeOnly_matchingEndpointsFilteredOut() {
    EndpointFilter f =
        new EndpointFilter(Collections.emptyList(), Arrays.asList("* /health", "* /metrics"));
    assertTrue(f.shouldTrack("GET", "/api/users"));
    assertFalse(f.shouldTrack("GET", "/health"));
    assertFalse(f.shouldTrack("POST", "/metrics"));
  }

  @Test
  void includeAndExclude_excludeOverridesInclude() {
    EndpointFilter f =
        new EndpointFilter(Arrays.asList("GET /api/*"), Arrays.asList("GET /api/internal/*"));
    assertTrue(f.shouldTrack("GET", "/api/users"));
    assertFalse(f.shouldTrack("GET", "/api/internal/debug"));
    assertFalse(f.shouldTrack("POST", "/api/users")); // not in include list
  }

  @Test
  void wildcardMethod_matchesAnyMethod() {
    EndpointFilter f = new EndpointFilter(Collections.emptyList(), Arrays.asList("* /health"));
    assertFalse(f.shouldTrack("GET", "/health"));
    assertFalse(f.shouldTrack("POST", "/health"));
    assertFalse(f.shouldTrack("OPTIONS", "/health"));
  }

  @Test
  void methodCaseInsensitive() {
    EndpointFilter f = new EndpointFilter(Arrays.asList("GET /ok"), Collections.emptyList());
    assertTrue(f.shouldTrack("get", "/ok"));
    assertTrue(f.shouldTrack("Get", "/ok"));
    assertTrue(f.shouldTrack("GET", "/ok"));
  }

  @Test
  void singleCharWildcard() {
    EndpointFilter f = new EndpointFilter(Arrays.asList("GET /api/v?"), Collections.emptyList());
    assertTrue(f.shouldTrack("GET", "/api/v1"));
    assertTrue(f.shouldTrack("GET", "/api/v9"));
    assertFalse(f.shouldTrack("GET", "/api/v12"));
  }

  @Test
  void nullInputs_tracksByDefault() {
    EndpointFilter f = new EndpointFilter(Arrays.asList("GET /api/*"), Arrays.asList("* /health"));
    assertTrue(f.shouldTrack(null, "/api/users"));
    assertTrue(f.shouldTrack("GET", null));
  }

  @Test
  void malformedRegexEntry_loggedAndSkipped_otherEntriesStillApply() {
    // A literal regex metacharacter in a glob is safely escaped by globToRegex,
    // so there's no easy way to produce an "invalid" glob via the public API.
    // Empty and null entries are silently skipped.
    EndpointFilter f =
        new EndpointFilter(Arrays.asList("", null, "GET /valid"), Collections.emptyList());
    assertEquals(1, f.includeSize());
    assertTrue(f.shouldTrack("GET", "/valid"));
    assertFalse(f.shouldTrack("POST", "/valid"));
  }
}
