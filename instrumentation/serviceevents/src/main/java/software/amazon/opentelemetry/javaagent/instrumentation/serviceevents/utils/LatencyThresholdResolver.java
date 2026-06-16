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
import software.amazon.opentelemetry.serviceevents.LatencyThresholdBridge;

/**
 * Resolves per-endpoint latency thresholds from a comma-separated list of glob patterns.
 *
 * <p>Each entry has the form {@code METHOD /route:threshold_ms}. The threshold is parsed from the
 * text after the LAST {@code :}, so routes may contain colons. The pattern part supports glob
 * wildcards {@code *} (zero or more chars) and {@code ?} (single char) on both the method and the
 * route; all other regex metacharacters are escaped. Because the list separator is a comma, a route
 * containing a literal comma (e.g. {@code ?q=a,b,c}) cannot be expressed verbatim — use a glob such
 * as {@code GET /search*} instead. This matches the comma delimiter used by the Python and JS SDKs.
 *
 * <p>Lookup matches {@code METHOD} (uppercased) + " " + {@code route} against the compiled patterns
 * in insertion order — first match wins. No match returns {@link Double#NaN}, signaling the caller
 * to fall back to the global default.
 *
 * <p>Malformed entries (missing {@code :}, non-numeric threshold, non-positive threshold, empty
 * pattern) are logged at {@link Level#SEVERE} and skipped — good neighbors still apply and the
 * agent continues to start.
 */
public final class LatencyThresholdResolver implements LatencyThresholdBridge {

  private static final Logger logger = Logger.getLogger(LatencyThresholdResolver.class.getName());

  private final List<CompiledEntry> entries;

  public LatencyThresholdResolver(List<String> rawEntries) {
    this.entries = compile(rawEntries);
  }

  @Override
  public double resolveThresholdMs(String method, String route) {
    if (method == null || route == null) {
      return Double.NaN;
    }
    String key = method.toUpperCase(Locale.ROOT) + " " + route;
    for (CompiledEntry entry : entries) {
      if (entry.pattern.matcher(key).matches()) {
        return entry.thresholdMs;
      }
    }
    return Double.NaN;
  }

  /** Number of accepted (well-formed) entries. */
  public int size() {
    return entries.size();
  }

  private static List<CompiledEntry> compile(List<String> rawEntries) {
    List<CompiledEntry> compiled = new ArrayList<>();
    if (rawEntries == null) {
      return compiled;
    }
    for (String raw : rawEntries) {
      if (raw == null) {
        continue;
      }
      String trimmed = raw.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int lastColon = trimmed.lastIndexOf(':');
      if (lastColon < 0) {
        logger.log(
            Level.SEVERE,
            "Ignoring OTEL_AWS_SERVICE_EVENTS_LATENCY_THRESHOLDS entry (missing ':' separator): "
                + trimmed);
        continue;
      }
      String patternPart = trimmed.substring(0, lastColon).trim();
      String thresholdPart = trimmed.substring(lastColon + 1).trim();
      if (patternPart.isEmpty()) {
        logger.log(
            Level.SEVERE,
            "Ignoring OTEL_AWS_SERVICE_EVENTS_LATENCY_THRESHOLDS entry (empty pattern): "
                + trimmed);
        continue;
      }
      double thresholdMs;
      try {
        thresholdMs = Double.parseDouble(thresholdPart);
      } catch (NumberFormatException e) {
        logger.log(
            Level.SEVERE,
            "Ignoring OTEL_AWS_SERVICE_EVENTS_LATENCY_THRESHOLDS entry (non-numeric threshold '"
                + thresholdPart
                + "'): "
                + trimmed);
        continue;
      }
      if (!(thresholdMs > 0) || Double.isNaN(thresholdMs) || Double.isInfinite(thresholdMs)) {
        logger.log(
            Level.SEVERE,
            "Ignoring OTEL_AWS_SERVICE_EVENTS_LATENCY_THRESHOLDS entry (threshold must be > 0): "
                + trimmed);
        continue;
      }
      Pattern pattern;
      try {
        pattern = Pattern.compile(globToRegex(patternPart));
      } catch (Exception e) {
        logger.log(
            Level.SEVERE,
            "Ignoring OTEL_AWS_SERVICE_EVENTS_LATENCY_THRESHOLDS entry (invalid glob '"
                + patternPart
                + "'): "
                + e.getMessage());
        continue;
      }
      compiled.add(new CompiledEntry(pattern, thresholdMs, patternPart));
      logger.log(
          Level.INFO, "Registered latency threshold: " + patternPart + " -> " + thresholdMs + "ms");
    }
    return compiled;
  }

  /**
   * Convert a glob expression to an anchored regex. Supports {@code *} and {@code ?}; every other
   * regex metacharacter is escaped.
   */
  static String globToRegex(String glob) {
    StringBuilder sb = new StringBuilder(glob.length() + 8);
    sb.append('^');
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      switch (c) {
        case '*':
          sb.append(".*");
          break;
        case '?':
          sb.append('.');
          break;
        case '.':
        case '(':
        case ')':
        case '+':
        case '|':
        case '^':
        case '$':
        case '{':
        case '}':
        case '[':
        case ']':
        case '\\':
          sb.append('\\').append(c);
          break;
        default:
          sb.append(c);
      }
    }
    sb.append('$');
    return sb.toString();
  }

  private static final class CompiledEntry {
    final Pattern pattern;
    final double thresholdMs;
    final String originalPattern;

    CompiledEntry(Pattern pattern, double thresholdMs, String originalPattern) {
      this.pattern = pattern;
      this.thresholdMs = thresholdMs;
      this.originalPattern = originalPattern;
    }
  }
}
