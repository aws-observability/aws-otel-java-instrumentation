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

package software.amazon.opentelemetry.di.app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.opentelemetry.di.app.service.InstrumentedService;

@RestController
public class DIController {

  private static final String SNAPSHOT_DIR = "/tmp/aws-di-snapshots";

  @Autowired private InstrumentedService instrumentedService;

  @Autowired private ObjectMapper objectMapper;

  @GetMapping("/success")
  public ResponseEntity<Map<String, Object>> success() {
    Map<String, Object> result = instrumentedService.processData("test-input");
    return ResponseEntity.ok(result);
  }

  @GetMapping("/probe")
  public ResponseEntity<Map<String, Object>> probe() {
    List<Integer> items = Arrays.asList(10, 20, 30);
    int total = instrumentedService.computeTotal(items);
    return ResponseEntity.ok(Map.of("total", total));
  }

  @GetMapping("/line-level")
  public ResponseEntity<Map<String, Object>> lineLevel() {
    int result = instrumentedService.calculateSum(5, 10);
    return ResponseEntity.ok(Map.of("result", result));
  }

  @GetMapping("/limited")
  public ResponseEntity<Map<String, Object>> limited() {
    String result = instrumentedService.limitedFunction();
    return ResponseEntity.ok(Map.of("result", result));
  }

  @GetMapping("/shared")
  public ResponseEntity<Map<String, Object>> shared() {
    String result = instrumentedService.sharedFunction();
    return ResponseEntity.ok(Map.of("result", result));
  }

  @GetMapping("/limits-string")
  public ResponseEntity<Map<String, Object>> limitsString() {
    // 500-char string — exceeds MAX_MAX_STRING_LENGTH (255), so captured value should be truncated
    String longString = "A".repeat(500);
    int len = instrumentedService.processLongString(longString);
    return ResponseEntity.ok(Map.of("length", len));
  }

  @GetMapping("/limits-collection")
  public ResponseEntity<Map<String, Object>> limitsCollection() {
    // 50-element list — exceeds MAX_MAX_COLLECTION_WIDTH (20), so only first 20 should be captured
    List<Integer> largeList = IntStream.rangeClosed(1, 50).boxed().collect(Collectors.toList());
    int size = instrumentedService.processLargeCollection(largeList);
    return ResponseEntity.ok(Map.of("size", size));
  }

  @GetMapping("/snapshots")
  @SuppressWarnings("unchecked")
  public ResponseEntity<List<Map<String, Object>>> getSnapshots() throws IOException {
    Path snapshotDir = Paths.get(SNAPSHOT_DIR);
    if (!Files.exists(snapshotDir)) {
      return ResponseEntity.ok(Collections.emptyList());
    }

    List<Map<String, Object>> snapshots = new ArrayList<>();
    try (Stream<Path> files = Files.list(snapshotDir)) {
      for (Path file :
          files
              .filter(f -> f.getFileName().toString().startsWith("snapshots."))
              .collect(Collectors.toList())) {
        for (String line : Files.readAllLines(file)) {
          if (!line.isBlank()) {
            snapshots.add(objectMapper.readValue(line, Map.class));
          }
        }
      }
    }
    return ResponseEntity.ok(snapshots);
  }
}
