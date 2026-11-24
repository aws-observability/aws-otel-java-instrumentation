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

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class AppController {

  @GetMapping("/health")
  @ResponseBody
  public ResponseEntity<String> health() {
    return ResponseEntity.ok("healthy");
  }

  @GetMapping("/success")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> success() {
    BusinessLogic logic = new BusinessLogic();
    String result = logic.process("test_data");
    int computed = HelperUtils.computeResult(42);
    return ResponseEntity.ok(Map.of("status", "ok", "result", result, "computed", computed));
  }

  @GetMapping("/client-error")
  @ResponseBody
  public ResponseEntity<Map<String, String>> clientError() {
    return ResponseEntity.badRequest().body(Map.of("error", "bad request"));
  }

  @GetMapping("/fault")
  @ResponseBody
  public ResponseEntity<Map<String, String>> fault() {
    return ResponseEntity.internalServerError().body(Map.of("error", "internal server error"));
  }

  @GetMapping("/exception")
  @ResponseBody
  public ResponseEntity<String> exception() {
    HelperUtils.validateInput(null);
    return ResponseEntity.ok("unreachable");
  }

  /**
   * CPU-intensive endpoint. Performs iterative computation to exercise function instrumentation and
   * produce a measurable request duration for EndpointSummary / service.function.duration coverage.
   */
  @GetMapping("/cpu-work")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> cpuWork() {
    BusinessLogic logic = new BusinessLogic();
    long result = logic.heavyComputation(500_000);
    int extra = HelperUtils.computeResult((int) (result % 1000));
    return ResponseEntity.ok(Map.of("status", "ok", "result", result, "extra", extra));
  }

  /**
   * Throws a nested exception with a cause chain to test exception info detail in incident
   * snapshots.
   */
  @GetMapping("/nested-exception")
  @ResponseBody
  public ResponseEntity<String> nestedException() {
    try {
      HelperUtils.riskyDatabaseCall();
    } catch (Exception e) {
      throw new RuntimeException("Service layer failure", e);
    }
    return ResponseEntity.ok("unreachable");
  }

  /**
   * Returns different error types based on the path variable. Useful for testing error breakdown in
   * EndpointSummary and multiple distinct IncidentSnapshots.
   */
  @GetMapping("/error/{type}")
  @ResponseBody
  public ResponseEntity<String> errorByType(@PathVariable String type) {
    switch (type) {
      case "illegal-argument":
        throw new IllegalArgumentException("Invalid argument: " + type);
      case "null-pointer":
        throw new NullPointerException("Null reference encountered");
      case "state":
        throw new IllegalStateException("Bad state for operation");
      default:
        throw new RuntimeException("Unknown error type: " + type);
    }
  }

  /**
   * Endpoint that performs moderate work, then returns success. Unlike /cpu-work, this is lighter
   * and suitable for tests that send many requests.
   */
  @GetMapping("/moderate-work")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> moderateWork() {
    BusinessLogic logic = new BusinessLogic();
    long result = logic.heavyComputation(50_000);
    return ResponseEntity.ok(Map.of("status", "ok", "result", result));
  }

  /**
   * Slow endpoint that performs CPU work then throws an exception. Designed to generate
   * IncidentSnapshots with a byte-instrumentation call_path — the nested computation produces a
   * multi-frame call path before the exception triggers the incident.
   */
  @GetMapping("/slow-error")
  @ResponseBody
  public ResponseEntity<String> slowError() {
    BusinessLogic logic = new BusinessLogic();
    logic.heavyComputation(5_000_000);
    throw new RuntimeException("Slow error after computation");
  }

  /**
   * Slow endpoint that completes normally with 200. Designed to trigger a latency-based
   * IncidentSnapshot (no exception, no /error re-dispatch — a clean test of the call-path capture
   * for the pure latency trigger path).
   */
  @GetMapping("/slow-success")
  @ResponseBody
  public ResponseEntity<Map<String, Object>> slowSuccess() throws InterruptedException {
    BusinessLogic logic = new BusinessLogic();
    long value = logic.slowOperation();
    int tuned = HelperUtils.computeResult((int) (value % 1000));
    return ResponseEntity.ok(Map.of("status", "ok", "value", value, "tuned", tuned));
  }
}
