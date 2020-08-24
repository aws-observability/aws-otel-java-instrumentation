package io.awsobservability.instrumentation.smoketests.springboot;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class AppController {

  @GetMapping("/hello")
  @ResponseBody
  public String hello() {
    return "Hi there!";
  }
}
