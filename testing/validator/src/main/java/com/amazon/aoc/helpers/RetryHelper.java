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

package com.amazon.aoc.helpers;

import com.amazon.aoc.enums.GenericConstants;
import java.util.concurrent.TimeUnit;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class RetryHelper {
  /**
   * retry executes the lambda, retry if the lambda throw exceptions.
   *
   * @param retryCount the total retry count
   * @param sleepInMilliSeconds sleep time among retries
   * @param retryable the lambda
   * @return false if retryCount exhausted
   * @throws Exception when the retry count is reached
   */
  public static boolean retry(
      int retryCount, int sleepInMilliSeconds, boolean throwExceptionInTheEnd, Retryable retryable)
      throws Exception {
    Exception exceptionInTheEnd = null;
    int initialCount = retryCount;
    while (retryCount-- > 0) {
      try {
        log.info("retry attempt left : {} ", retryCount);
        retryable.execute();
        return true;
      } catch (Exception ex) {
        exceptionInTheEnd = ex;
        if (retryCount != 0) { // don't sleep before leave this loop
          log.info(
              "retrying after {} seconds", TimeUnit.MILLISECONDS.toSeconds(sleepInMilliSeconds));
          TimeUnit.MILLISECONDS.sleep(sleepInMilliSeconds);
        }
      }
    }

    log.error("All {} retries exhausted", initialCount);
    if (throwExceptionInTheEnd) {
      throw exceptionInTheEnd;
    }
    return false;
  }

  /**
   * retry executes lambda with default retry count(10) and sleep seconds(10).
   *
   * @param retryable the lambda
   * @throws Exception when the retry count is reached
   */
  public static void retry(Retryable retryable) throws Exception {
    retry(
        Integer.valueOf(GenericConstants.MAX_RETRIES.getVal()),
        Integer.valueOf(GenericConstants.SLEEP_IN_MILLISECONDS.getVal()),
        true,
        retryable);
  }

  /**
   * retry executes lambda with default sleeping seconds 10s.
   *
   * @param retryCount the total retry count
   * @param retryable the lambda function to be executed
   * @throws Exception when the retry count is reached
   */
  public static void retry(int retryCount, Retryable retryable) throws Exception {
    retry(
        retryCount,
        Integer.valueOf(GenericConstants.SLEEP_IN_MILLISECONDS.getVal()),
        true,
        retryable);
  }
}
