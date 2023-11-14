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

public enum ExceptionCode {
  S3_KEY_ALREADY_EXIST(20001, "s3 key is existed already"),
  FAILED_AFTER_RETRY(20004, "failed after retry"),
  S3_BUCKET_IS_EXISTED_IN_CURRENT_ACCOUNT(20013, "s3 bucket is already existed in your account"),
  S3_BUCKET_IS_EXISTED_GLOBALLY(20014, "s3 bucket is already existed globally"),

  EXPECTED_METRIC_NOT_FOUND(30001, "expected metric not found"),
  EXPECTED_LOG_NOT_FOUND(30002, "expected log not found"),

  // validating errors
  TRACE_ID_NOT_MATCHED(50001, "trace id not matched"),
  DATA_MODEL_NOT_MATCHED(50006, "data model not matched"),
  TRACE_SPAN_LIST_NOT_MATCHED(50002, "trace span list has different length"),
  TRACE_SPAN_NOT_MATCHED(50003, "trace span not matched"),
  TRACE_LIST_NOT_MATCHED(50004, "trace list has different length"),
  DATA_EMITTER_UNAVAILABLE(50005, "the data emitter is unavailable to ping"),
  EMPTY_LIST(50007, "list is empty or null"),
  LOG_FORMAT_NOT_MATCHED(50008, "log format not matched"),
  HEALTH_STATUS_NOT_MATCHED(50009, "health_check status not matched"),

  // build validator
  VALIDATION_TYPE_NOT_EXISTED(60001, "validation type not existed"),
  CALLER_TYPE_NOT_EXISTED(60002, "caller type not existed"),

  // alarm validation
  ALARM_BAKING(70001, "alarms still need to be baked"),

  // mocked server
  MOCKED_SERVER_NOT_AVAILABLE(80001, "mocked server is not available"),
  MOCKED_SERVER_NOT_RECEIVE_DATA(80002, "mocked server not receive data"),

  // clients failed
  CORTEX_CLIENT_REQUEST_FAILED(90001, "request to pull mode sample app failed"),
  PULL_MODE_SAMPLE_APP_CLIENT_REQUEST_FAILED(90001, "request to pull mode sample app failed"),

  // ecs resource
  ECS_RESOURCES_NOT_FOUND(100001, "awaiting on ECS resources to be ready"),
  ;
  private int code;
  private String message;

  ExceptionCode(int code, String message) {
    this.code = code;
    this.message = message;
  }

  public int getCode() {
    return code;
  }

  public String getMessage() {
    return message;
  }
}
