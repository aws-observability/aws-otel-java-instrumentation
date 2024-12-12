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

package software.amazon.opentelemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

@Controller
public class AppController {

  @Autowired private JdbcTemplate jdbcTemplate;

  private final RestTemplate client = new RestTemplate();
  static final Logger logger = LoggerFactory.getLogger(AppController.class);

  @EventListener(ApplicationReadyEvent.class)
  public void prepareDB() {
    jdbcTemplate.execute("create table employee (id int, name varchar(255))");
    jdbcTemplate.execute("insert into employee (id, name) values (1, 'A')");
    logger.info("Application Ready");
  }

  @GetMapping("/success/CREATE database")
  @ResponseBody
  public ResponseEntity<String> successCreateDatabase() {
    jdbcTemplate.execute("create database testdb2");
    return ResponseEntity.ok().body("success");
  }

  @GetMapping("/success/SELECT")
  @ResponseBody
  public ResponseEntity<String> successSelect() {
    int count = jdbcTemplate.queryForObject("select count(*) from employee", Integer.class);
    return (count == 1)
        ? ResponseEntity.ok().body("success")
        : ResponseEntity.badRequest().body("failed");
  }

  @GetMapping("/fault/SELECT")
  @ResponseBody
  public ResponseEntity<String> failureSelect() {
    int count = jdbcTemplate.queryForObject("select count(*) from userrr", Integer.class);
    return ResponseEntity.ok().body("success");
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity handleGenericException(Exception ex) {
    if (ex instanceof HttpServerErrorException.InternalServerError) {
      return ResponseEntity.internalServerError().build();
    } else if (ex instanceof HttpClientErrorException.BadRequest) {
      return ResponseEntity.badRequest().build();
    } else if (ex instanceof BadSqlGrammarException) {
      return ResponseEntity.internalServerError().body("fault");
    }
    return ResponseEntity.ok().build();
  }
}
