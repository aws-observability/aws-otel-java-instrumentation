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

package com.amazon.aoc.validators;

import com.amazon.aoc.callers.HttpCaller;
import com.amazon.aoc.callers.ICaller;
import com.amazon.aoc.exception.BaseException;
import com.amazon.aoc.exception.ExceptionCode;
import com.amazon.aoc.fileconfigs.FileConfig;
import com.amazon.aoc.models.Context;
import com.amazon.aoc.models.ValidationConfig;

public class ValidatorFactory {
  private Context context;

  public ValidatorFactory(Context context) {
    this.context = context;
  }

  /**
   * create and init validator base on config.
   *
   * @param validationConfig config from file
   * @return validator object
   * @throws Exception when there's no matched validator
   */
  public IValidator launchValidator(ValidationConfig validationConfig) throws Exception {
    // get validator
    IValidator validator;
    FileConfig expectedData = null;
    switch (validationConfig.getValidationType()) {
      case "trace":
        validator = new TraceValidator();
        expectedData = validationConfig.getExpectedTraceTemplate();
        break;
      case "cw-metric":
        validator = new CWMetricValidator();
        expectedData = validationConfig.getExpectedMetricTemplate();
        break;
      case "cw-log":
        validator = new CWLogValidator();
        expectedData = validationConfig.getExpectedLogStructureTemplate();
        break;
      default:
        throw new BaseException(ExceptionCode.VALIDATION_TYPE_NOT_EXISTED);
    }

    // get caller
    ICaller caller;
    switch (validationConfig.getCallingType()) {
      case "http":
        caller = new HttpCaller(context.getEndpoint(), validationConfig.getHttpPath());
        break;
      case "http-with-body":
        // ONLY ONE OF THESE CAN BE USED PER VALIDATOR CALL
        caller =
            new HttpCaller(
                context.getEndpoint(), validationConfig.getHttpPath(), context.getRequestBody());
        break;
      case "none":
        caller = null;
        break;
      default:
        throw new BaseException(ExceptionCode.CALLER_TYPE_NOT_EXISTED);
    }

    // init validator
    validator.init(this.context, validationConfig, caller, expectedData);
    return validator;
  }
}
