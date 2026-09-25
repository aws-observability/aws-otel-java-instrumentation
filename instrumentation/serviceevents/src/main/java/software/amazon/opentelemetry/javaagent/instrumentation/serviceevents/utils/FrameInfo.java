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

public final class FrameInfo {
  public final String typeName;
  public final String methodName;
  public final String fileName;
  public final int lineNumber;

  public FrameInfo(String typeName, String methodName, String fileName, int lineNumber) {
    this.typeName = typeName;
    this.methodName = methodName;
    this.fileName = fileName;
    this.lineNumber = lineNumber;
  }
}
