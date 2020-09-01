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

package io.awsobservability.instrumentation.smoketests.springboot;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.RestTemplate;

@Controller
public class AppController {

  private final RestTemplate client = new RestTemplate();

  @GetMapping("/hello")
  @ResponseBody
  public ResponseEntity<String> hello() {
    var backendResponse = client.getForEntity("http://localhost:8080/backend", String.class);
    var response = ResponseEntity.ok();
    backendResponse.getHeaders().forEach((name, values) -> response.header(name, values.get(0)));
    return response.build();
  }

  @GetMapping("/backend")
  @ResponseBody
  public ResponseEntity<String> backend(@RequestHeader Map<String, String> headers) {
    var response = ResponseEntity.ok();
    headers.forEach((name, value) -> response.header("received-" + name, value));
    return response.build();
  }
}
