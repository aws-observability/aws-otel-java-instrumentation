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

import com.amazon.aoc.callers.ICaller;
import com.amazon.aoc.exception.BaseException;
import com.amazon.aoc.exception.ExceptionCode;
import com.amazon.aoc.fileconfigs.FileConfig;
import com.amazon.aoc.helpers.MustacheHelper;
import com.amazon.aoc.helpers.RetryHelper;
import com.amazon.aoc.models.Context;
import com.amazon.aoc.models.ValidationConfig;
import com.amazon.aoc.services.CloudWatchService;
import com.amazonaws.services.logs.model.FilteredLogEvent;
import com.github.wnameless.json.flattener.FlattenMode;
import com.github.wnameless.json.flattener.JsonFlattener;
import com.github.wnameless.json.flattener.JsonifyArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class CWLogValidator implements IValidator {
  private static int DEFAULT_MAX_RETRY_COUNT = 15;

  private MustacheHelper mustacheHelper = new MustacheHelper();
  private ICaller caller;
  private Context context;
  private FileConfig expectedLog;
  private CloudWatchService cloudWatchService;
  private int maxRetryCount;

  @Override
  public void validate() throws Exception {
    log.info("Start CW Log Validation for path {}", caller.getCallingPath());

    // Get expected values for log attributes we want to check
    JsonifyArrayList<Map<String, Object>> expectedAttributesArray = this.getExpectedAttributes();
    log.info("Values of expected logs: {}", expectedAttributesArray);

    RetryHelper.retry(
        this.maxRetryCount,
        () -> {

          // Call sample app to generate logs
          this.caller.callSampleApp();

          // Iterate through each expected template to check if the log is present
          for (Map<String, Object> expectedAttributes : expectedAttributesArray) {
            // All attributes are in REGEX for preciseness except operation, remoteService and
            // remoteOperation
            // which are in normal text as they are needed for
            // the filter expressions for retrieving the actual logs.
            log.info("Searching for expected log: {}", expectedAttributes);
            String operation = (String) expectedAttributes.get("Operation");
            String remoteService = (String) expectedAttributes.get("RemoteService");
            String remoteOperation = (String) expectedAttributes.get("RemoteOperation");

            Map<String, Object> actualLog =
                this.getActualLog(operation, remoteService, remoteOperation);
            log.info("Value of an actual log: {}", actualLog);

            if (actualLog == null) throw new BaseException(ExceptionCode.EXPECTED_LOG_NOT_FOUND);

            validateLogs(expectedAttributes, actualLog);
          }
        });

    log.info("Log validation is passed for path {}", caller.getCallingPath());
  }

  private void validateLogs(Map<String, Object> expectedAttributes, Map<String, Object> actualLog)
      throws Exception {
    for (Map.Entry<String, Object> entry : expectedAttributes.entrySet()) {
      String expectedKey = entry.getKey();
      Object expectedValue = entry.getValue();

      if (!actualLog.containsKey(expectedKey)) {
        log.error("Log Validation Failure: Key {} does not exist", expectedKey);
        throw new BaseException(ExceptionCode.EXPECTED_LOG_NOT_FOUND);
      }

      Pattern pattern = Pattern.compile(expectedValue.toString());
      Matcher matcher = pattern.matcher(actualLog.get(expectedKey).toString());

      if (!matcher.find()) {
        log.error(
            "Log Validation Failure: Value for Key: {} was expected to be: {}, but actual was: {}",
            expectedKey,
            expectedValue,
            actualLog.get(expectedKey));
        throw new BaseException(ExceptionCode.DATA_MODEL_NOT_MATCHED);
      }
    }
  }

  private JsonifyArrayList<Map<String, Object>> getExpectedAttributes() throws Exception {
    JsonifyArrayList<Map<String, Object>> flattenedJsonMapForExpectedLogArray = null;
    String jsonExpectedLog = mustacheHelper.render(this.expectedLog, context);

    try {
      // flattened JSON object to a map while keeping the arrays
      Map<String, Object> flattenedJsonMapForExpectedLog =
          new JsonFlattener(jsonExpectedLog)
              .withFlattenMode(FlattenMode.KEEP_ARRAYS)
              .flattenAsMap();

      flattenedJsonMapForExpectedLogArray =
          (JsonifyArrayList) flattenedJsonMapForExpectedLog.get("root");
    } catch (Exception e) {
      e.printStackTrace();
    }

    return flattenedJsonMapForExpectedLogArray;
  }

  private Map<String, Object> getActualLog(
      String operation, String remoteService, String remoteOperation) throws Exception {
    String filterPattern = null;

    // Dependency calls will have the remoteService and remoteOperation attribute, but service calls
    // will not. A service call will have
    // null remoteService and null remoteOperation and the filter expression must be adjusted
    // accordingly.
    if (remoteService == null && remoteOperation == null) {
      filterPattern =
          String.format(
              "{ ($.Service = %s) && ($.Operation = \"%s\") && "
                  + "($.RemoteService NOT EXISTS) && ($.RemoteOperation NOT EXISTS) }",
              context.getServiceName(), operation);
    } else {
      filterPattern =
          String.format(
              "{ ($.Service = %s) && ($.Operation = \"%s\") && "
                  + "($.RemoteService = \"%s\") && ($.RemoteOperation = \"%s\") }",
              context.getServiceName(), operation, remoteService, remoteOperation);
    }
    log.info("Filter Pattern for Log Search: " + filterPattern);

    List<FilteredLogEvent> retrievedLogs =
        this.cloudWatchService.filterLogs(
            context.getLogGroup(),
            filterPattern,
            System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(5),
            10);

    if (retrievedLogs == null || retrievedLogs.isEmpty()) {
      throw new BaseException(ExceptionCode.EMPTY_LIST);
    }

    return JsonFlattener.flattenAsMap(retrievedLogs.get(0).getMessage());
  }

  @Override
  public void init(
      Context context,
      ValidationConfig validationConfig,
      ICaller caller,
      FileConfig expectedLogTemplate)
      throws Exception {
    this.context = context;
    this.caller = caller;
    this.expectedLog = expectedLogTemplate;
    this.cloudWatchService = new CloudWatchService(context.getRegion());
    this.maxRetryCount = DEFAULT_MAX_RETRY_COUNT;
  }
}
