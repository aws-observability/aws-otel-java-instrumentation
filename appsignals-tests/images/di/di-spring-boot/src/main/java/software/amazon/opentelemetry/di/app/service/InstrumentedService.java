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

package software.amazon.opentelemetry.di.app.service;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Service containing functions to be instrumented by DI. Each function corresponds to a DI
 * configuration (PROBE or BREAKPOINT).
 */
@Service
public class InstrumentedService {

  /**
   * Function-level BREAKPOINT target. Configured with lineNumber=0 for method-level capture.
   *
   * @param input the input string to process
   * @return a map containing input, processed, and status fields
   */
  public Map<String, Object> processData(String input) {
    String processed = input.toUpperCase();
    return Map.of("input", input, "processed", processed, "status", "success");
  }

  /**
   * PROBE target - permanent instrumentation, no hit limit.
   *
   * @param items list of integers to sum
   * @return total sum of all items
   */
  public int computeTotal(List<Integer> items) {
    int total = 0;
    for (int item : items) {
      total += item;
    }
    return total; // Returns 60 for [10, 20, 30]
  }

  /**
   * Line-level BREAKPOINT target. Configured with specific lineNumber > 0.
   *
   * @param a first number
   * @param b second number
   * @return sum of a and b
   */
  public int calculateSum(int a, int b) {
    int result = a + b; // Line 62 - line-level breakpoint here
    return result;
  }

  /**
   * BREAKPOINT with MaxHits=3 (only generates limited snapshots).
   *
   * @return a fixed result string
   */
  public String limitedFunction() {
    return "limited-result";
  }

  /**
   * Function with both PROBE and BREAKPOINT configs.
   *
   * @return a fixed result string
   */
  public String sharedFunction() {
    return "shared-result";
  }

  /**
   * BREAKPOINT target for string truncation limit validation. The config requests
   * MaxStringLength=9999 which gets clamped to 255. The input string is 500 chars, so the captured
   * value should be truncated at 255.
   *
   * @param longString a string longer than the max capture length
   * @return the original string length
   */
  public int processLongString(String longString) {
    return longString.length();
  }

  /**
   * BREAKPOINT target for collection width limit validation. The config requests
   * MaxCollectionWidth=9999 which gets clamped to 20. The input list has 50 elements, so only the
   * first 20 should be captured.
   *
   * @param largeList a list larger than the max collection width
   * @return the original list size
   */
  public int processLargeCollection(List<Integer> largeList) {
    return largeList.size();
  }
}
