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

package software.amazon.opentelemetry.javaagent.bootstrap.di;

/** Lightweight holder for exception data extracted on the application thread. */
public final class ThrowableData {

  private final String type;
  private final String message;
  private final StackTraceElement[] stackTrace;

  public ThrowableData(String type, String message, StackTraceElement[] stackTrace) {
    this.type = type;
    this.message = message;
    this.stackTrace = stackTrace != null ? stackTrace.clone() : null;
  }

  public String getType() {
    return type;
  }

  public String getMessage() {
    return message;
  }

  public StackTraceElement[] getStackTrace() {
    return stackTrace != null ? stackTrace.clone() : null;
  }
}
