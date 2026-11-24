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

package software.amazon.opentelemetry.serviceevents;

import java.util.ArrayList;
import java.util.List;

/** Investigation data for incident call path tracking. */
public class InvestigationData {
  /**
   * Hard cap on real call-path frames retained per request. The hoisted methodExit records a frame
   * for every instrumented call (not just sampled ones), so under {@code auto}/{@code never}
   * sampling a high-call-volume request would otherwise grow this list without bound and could push
   * a serialized IncidentSnapshot past the 1 MB CloudWatch OTLP Logs limit. At ~150-250 B/frame,
   * 1024 frames is ~256 KB — well under the limit with room for the stack trace and other fields,
   * yet far above any legitimate request's frame count. Python/JS apply the same cap.
   */
  static final int MAX_CALL_PATH_ENTRIES = 1024;

  /**
   * Marker appended once when the cap is exceeded. {@code durationNs == 0} trips {@code is_partial}
   * (computed from the call path as {@code any(durationNs == 0)} in the IncidentSnapshot emitter,
   * matching Python/JS), and the zero duration is stripped from the serialized frame.
   */
  static final String CALL_PATH_TRUNCATION_SENTINEL = "<call_path_truncated>";

  private final List<CallPathEntry> callPath = new ArrayList<CallPathEntry>();
  private ExceptionData exception;

  public InvestigationData() {}

  public void addEntry(String functionId, String caller, long durationNs) {
    addEntry(functionId, caller, durationNs, false, false);
  }

  public void addEntry(
      String functionId, String caller, long durationNs, boolean error, boolean isAsync) {
    int size = callPath.size();
    if (size > MAX_CALL_PATH_ENTRIES) {
      // Already at [MAX real frames + sentinel] — drop everything after.
      return;
    }
    if (size == MAX_CALL_PATH_ENTRIES) {
      // This frame overflows the cap: keep the first MAX, then a single truncation sentinel.
      callPath.add(new CallPathEntry(CALL_PATH_TRUNCATION_SENTINEL, null, 0L, false, false));
      return;
    }
    callPath.add(new CallPathEntry(functionId, caller, durationNs, error, isAsync));
  }

  public void setException(String type, String message, String stackTrace) {
    this.exception = new ExceptionData(type, message, stackTrace);
  }

  public List<CallPathEntry> getCallPath() {
    return callPath;
  }

  public ExceptionData getException() {
    return exception;
  }
}
