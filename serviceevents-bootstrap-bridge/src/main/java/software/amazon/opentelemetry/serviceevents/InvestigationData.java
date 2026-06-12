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
  private final List<CallPathEntry> callPath = new ArrayList<CallPathEntry>();
  private ExceptionData exception;

  public InvestigationData() {}

  public void addEntry(String functionId, String caller, long durationNs) {
    addEntry(functionId, caller, durationNs, false, false);
  }

  public void addEntry(
      String functionId, String caller, long durationNs, boolean error, boolean isAsync) {
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
