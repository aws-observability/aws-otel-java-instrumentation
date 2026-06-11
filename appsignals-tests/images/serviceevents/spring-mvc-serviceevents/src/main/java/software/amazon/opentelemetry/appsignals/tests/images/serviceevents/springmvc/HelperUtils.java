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

import java.io.IOException;

public class HelperUtils {

  public static int computeResult(int x) {
    return x * 2;
  }

  public static boolean validateInput(Object value) {
    if (value == null) {
      throw new RuntimeException("Test unhandled exception");
    }
    return true;
  }

  /**
   * Simulates a risky database call that throws a checked exception. Used by /nested-exception
   * endpoint to test cause chain in incident snapshots.
   */
  public static void riskyDatabaseCall() throws IOException {
    throw new IOException("Connection refused: database-host:5432");
  }

  /**
   * Shuffles a value to prevent JIT optimization of hot loops. Used by {@link
   * BusinessLogic#heavyComputation(int)} to ensure the computation isn't elided.
   */
  public static long shuffleValue(long value) {
    return (value ^ (value >>> 16)) * 0x45d9f3bL;
  }
}
