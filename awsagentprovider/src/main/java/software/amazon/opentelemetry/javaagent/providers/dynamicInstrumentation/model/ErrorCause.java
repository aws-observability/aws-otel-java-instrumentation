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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model;

/**
 * Error causes for failed instrumentation configurations.
 *
 * <p>Matches Python ErrorCause enum exactly.
 */
public enum ErrorCause {
  /** Source file not found during transformation */
  FILE_NOT_FOUND,

  /** Method not found in target class */
  METHOD_NOT_FOUND,

  /** Line number not executable (no bytecode at that line) */
  LINE_NOT_EXECUTABLE,

  /** Method is overloaded, ambiguous target */
  OVERLOADED_METHODS,

  /** Language mismatch (non-Java configuration) */
  LANGUAGE_MISMATCH,

  /** Runtime error during transformation or execution */
  RUNTIME_ERROR,

  /** Target method type is not supported (e.g., constructors, static initializers) */
  UNSUPPORTED_TARGET
}
