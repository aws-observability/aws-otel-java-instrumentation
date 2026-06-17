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

/** Call path entry for investigation data. */
public class CallPathEntry {
  public final String functionId;
  public final String caller;
  public final long durationNs;
  public final boolean error;
  public final boolean isAsync;

  public CallPathEntry(String functionId, String caller, long durationNs) {
    this(functionId, caller, durationNs, false, false);
  }

  public CallPathEntry(
      String functionId, String caller, long durationNs, boolean error, boolean isAsync) {
    this.functionId = functionId;
    this.caller = caller;
    this.durationNs = durationNs;
    this.error = error;
    this.isAsync = isAsync;
  }

  public String getFunctionId() {
    return functionId;
  }

  public String getCaller() {
    return caller;
  }

  public long getDurationNs() {
    return durationNs;
  }

  public boolean isError() {
    return error;
  }

  public boolean isAsync() {
    return isAsync;
  }
}
