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

package com.amazon.aoc.exception;

import lombok.Getter;

@Getter
public class BaseException extends Exception {
  private int code;
  private String message;

  public BaseException(ExceptionCode exceptionCode) {
    this.code = exceptionCode.getCode();
    this.message = exceptionCode.getMessage();
  }

  public BaseException(ExceptionCode exceptionCode, String message) {
    this.code = exceptionCode.getCode();
    this.message = message;
  }
}
