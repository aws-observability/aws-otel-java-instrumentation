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

package software.amazon.opentelemetry.appsignals.tests.images.httpservers.springmvc;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.RestTemplate;

@Controller
public class AppController {

  private final RestTemplate client = new RestTemplate();

  @GetMapping("/success")
  @ResponseBody
  public ResponseEntity<String> success() {
    var response = ResponseEntity.ok();
    OpenTelemetry otel = GlobalOpenTelemetry.get();

    Span span =
        otel.getTracer("test-image")
            .spanBuilder("marker-span")
            .setSpanKind(SpanKind.INTERNAL)
            .startSpan();
    try (Scope scope = span.makeCurrent()) {
      /** noop * */
    }

    span.end();

    return response.build();
  }

  @GetMapping("/users/{userId}/orders/{orderId}")
  @ResponseBody
  public ResponseEntity<String> route(
      @PathVariable String userId,
      @PathVariable String orderId,
      @RequestParam Optional<String> filter) {
    return ResponseEntity.ok().build();
  }

  @GetMapping("/error")
  @ResponseBody
  public ResponseEntity<String> error() {
    var response = ResponseEntity.badRequest();
    return response.build();
  }

  @GetMapping("/fault")
  @ResponseBody
  public ResponseEntity<String> failure() {
    return ResponseEntity.internalServerError().build();
  }
}
