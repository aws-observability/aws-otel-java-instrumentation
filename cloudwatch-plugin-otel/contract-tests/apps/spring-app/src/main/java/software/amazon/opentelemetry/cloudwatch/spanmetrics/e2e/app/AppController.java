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

package software.amazon.opentelemetry.cloudwatch.spanmetrics.e2e.app;

import jakarta.annotation.PostConstruct;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/** HTTP endpoints exercised by the e2e tests. */
@RestController
public class AppController {

  private final TestItemRepository repository;

  public AppController(TestItemRepository repository) {
    this.repository = repository;
  }

  @PostConstruct
  void seed() {
    TestItem item = new TestItem();
    item.setId(1L);
    item.setName("span-metrics");
    repository.save(item);
  }

  @GetMapping("/ping")
  @ResponseBody
  public ResponseEntity<String> ping() {
    return ResponseEntity.ok("pong");
  }

  @GetMapping("/db")
  @ResponseBody
  public ResponseEntity<String> db() {
    long count = repository.count();
    return ResponseEntity.ok("count=" + count);
  }

  @GetMapping("/error")
  @ResponseBody
  public ResponseEntity<String> error() {
    // 5xx so the framework instrumentation marks the server span's status as ERROR.
    return ResponseEntity.status(500).body("error");
  }
}
