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

package software.amazon.opentelemetry.appsignals.tests.images.serviceevents.springmvc;

public class BusinessLogic {

  public String process(String data) {
    int result = HelperUtils.computeResult(data.length());
    return "processed:" + result;
  }

  /**
   * CPU-intensive computation. Performs iterative math operations to keep the thread busy and
   * produce a measurable request duration.
   *
   * @param iterations Number of iterations (500_000 → ~50-200ms depending on CPU)
   * @return Computed result
   */
  public long heavyComputation(int iterations) {
    long accumulator = 0;
    for (int i = 0; i < iterations; i++) {
      accumulator += HelperUtils.computeResult(i);
      // Add some branching to prevent trivial optimization
      if (i % 1000 == 0) {
        accumulator = HelperUtils.shuffleValue(accumulator);
      }
    }
    return accumulator;
  }

  /**
   * Simulates a slow I/O-bound operation that blocks the request thread long enough to exceed the
   * latency threshold. Used by the {@code /slow-success} endpoint to trigger a pure latency-based
   * IncidentSnapshot (no exception, no /error re-dispatch).
   */
  public long slowOperation() throws InterruptedException {
    long accumulator = HelperUtils.computeResult(7);
    Thread.sleep(3_000); // 3s — comfortably above the 2s threshold set in contract tests
    accumulator += HelperUtils.shuffleValue(accumulator);
    return accumulator;
  }
}
