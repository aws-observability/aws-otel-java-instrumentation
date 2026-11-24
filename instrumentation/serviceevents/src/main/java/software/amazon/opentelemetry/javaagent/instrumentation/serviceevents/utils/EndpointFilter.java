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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Glob-pattern filter for ServiceEvents endpoint tracking, applied before an HTTP request is
 * recorded into the per-endpoint aggregation. Mirrors the JS SDK's {@code shouldTrackEndpoint} in
 * {@code src/serviceevents/config.ts}.
 *
 * <p>Semantics:
 *
 * <ol>
 *   <li>If include patterns are empty, all endpoints are tracked (the default).
 *   <li>Otherwise the endpoint must match at least one include pattern to be considered.
 *   <li>Any endpoint matching an exclude pattern is filtered out, after the include check.
 * </ol>
 *
 * <p>Patterns match the string {@code METHOD SPACE route} (method uppercased). Wildcards:
 *
 * <ul>
 *   <li>{@code *} matches zero or more characters
 *   <li>{@code ?} matches one character
 * </ul>
 *
 * <p>Malformed regex (after glob-to-regex conversion) is logged at {@link Level#SEVERE} and the
 * offending pattern is skipped; other entries still apply.
 */
public final class EndpointFilter {

  private static final Logger logger = Logger.getLogger(EndpointFilter.class.getName());

  private final List<Pattern> includePatterns;
  private final List<Pattern> excludePatterns;

  public EndpointFilter(List<String> includeGlobs, List<String> excludeGlobs) {
    this.includePatterns = compile(includeGlobs, "INCLUDE");
    this.excludePatterns = compile(excludeGlobs, "EXCLUDE");
  }

  /** Return {@code true} iff this endpoint should be tracked given the configured filters. */
  public boolean shouldTrack(String method, String route) {
    if (method == null || route == null) {
      return true;
    }
    String key = method.toUpperCase(Locale.ROOT) + " " + route;

    if (!includePatterns.isEmpty()) {
      boolean matched = false;
      for (Pattern p : includePatterns) {
        if (p.matcher(key).matches()) {
          matched = true;
          break;
        }
      }
      if (!matched) {
        return false;
      }
    }

    for (Pattern p : excludePatterns) {
      if (p.matcher(key).matches()) {
        return false;
      }
    }
    return true;
  }

  /** Exposed for tests. */
  public int includeSize() {
    return includePatterns.size();
  }

  /** Exposed for tests. */
  public int excludeSize() {
    return excludePatterns.size();
  }

  private static List<Pattern> compile(List<String> globs, String envSuffix) {
    List<Pattern> out = new ArrayList<>();
    if (globs == null) {
      return out;
    }
    for (String raw : globs) {
      if (raw == null) {
        continue;
      }
      String trimmed = raw.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      try {
        out.add(Pattern.compile(LatencyThresholdResolver.globToRegex(trimmed)));
      } catch (Exception e) {
        logger.log(
            Level.SEVERE,
            "Ignoring OTEL_AWS_SERVICE_EVENTS_ENDPOINT_"
                + envSuffix
                + "_PATTERNS entry (invalid glob '"
                + trimmed
                + "'): "
                + e.getMessage());
      }
    }
    return out;
  }
}
