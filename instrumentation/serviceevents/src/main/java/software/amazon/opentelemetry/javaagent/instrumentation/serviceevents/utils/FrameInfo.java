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

/**
 * One stack frame as read from the JFR.
 *
 * <p>For a Java frame, {@code typeName} is the dotted declaring class and {@code libraryName} is
 * empty. For a native, C++ or kernel frame, {@code typeName} is empty and {@code libraryName} is the
 * shared library the frame belongs to (e.g. {@code libc.so.6}), or empty when unknown. {@code
 * fileName} is the source file when authoritatively known, otherwise empty. Null arguments are
 * normalized to empty strings.
 */
public final class FrameInfo {
  public final String typeName;
  public final String methodName;
  public final String fileName;
  public final int lineNumber;
  public final String libraryName;

  public FrameInfo(String typeName, String methodName, String fileName, int lineNumber) {
    this(typeName, methodName, fileName, lineNumber, "");
  }

  public FrameInfo(
      String typeName, String methodName, String fileName, int lineNumber, String libraryName) {
    this.typeName = typeName == null ? "" : typeName;
    this.methodName = methodName == null ? "" : methodName;
    this.fileName = fileName == null ? "" : fileName;
    this.lineNumber = lineNumber;
    this.libraryName = libraryName == null ? "" : libraryName;
  }
}
