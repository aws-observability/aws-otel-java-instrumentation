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

package com.amazon.sampleapp;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

@Controller
public class AppController {

  private final RestTemplate client = new RestTemplate();

  @GetMapping("/success")
  @ResponseBody
  public ResponseEntity<String> success() {
    return client.getForEntity("http://backend:8080/backend/success", String.class);
  }

  @GetMapping("/error")
  @ResponseBody
  public ResponseEntity<String> backend() {
    return client.getForEntity("http://backend:8080/backend/error", String.class);
  }

  @GetMapping("/fault")
  @ResponseBody
  public ResponseEntity<String> failure() {
    return client.getForEntity("http://backend:8080/backend/fault", String.class);
  }

  // backend //
  @GetMapping("/backend/success")
  @ResponseBody
  public ResponseEntity<String> backendSuccess() {
    var response = ResponseEntity.ok();
    return response.build();
  }

  @GetMapping("/backend/error")
  @ResponseBody
  public ResponseEntity<String> backendError() {
    var response = ResponseEntity.badRequest();
    return response.build();
  }

  @GetMapping("/backend/fault")
  @ResponseBody
  public ResponseEntity<String> backendFailure() {
    return ResponseEntity.internalServerError().build();
  }

  @PostMapping("/success/postmethod")
  @ResponseBody
  public ResponseEntity<String> success(@RequestBody String body) {
    return client.postForEntity(
        "http://backend:8080/backend/success/postmethod", body, String.class);
  }

  @PostMapping("backend/success/postmethod")
  @ResponseBody
  public ResponseEntity<String> backendSuccess(@RequestBody String body) {
    return ResponseEntity.ok().build();
  }

  @PostMapping("/error/postmethod")
  @ResponseBody
  public ResponseEntity<String> error(@RequestBody String body) {
    return client.postForEntity("http://backend:8080/backend/error/postmethod", body, String.class);
  }

  @PostMapping("backend/error/postmethod")
  @ResponseBody
  public ResponseEntity<String> backendError(@RequestBody String body) {
    var response = ResponseEntity.badRequest();
    return response.build();
  }

  @PostMapping("/fault/postmethod")
  @ResponseBody
  public ResponseEntity<String> failure(@RequestBody String body) {
    return client.postForEntity("http://backend:8080/backend/fault/postmethod", body, String.class);
  }

  @PostMapping("backend/fault/postmethod")
  @ResponseBody
  public ResponseEntity<String> backendFailure(@RequestBody String body) {
    return ResponseEntity.internalServerError().build();
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity handleGenericException(Exception ex) {
    if (ex instanceof HttpServerErrorException.InternalServerError) {
      return ResponseEntity.internalServerError().build();
    } else if (ex instanceof HttpClientErrorException.BadRequest) {
      return ResponseEntity.badRequest().build();
    }
    return ResponseEntity.ok().build();
  }
}
