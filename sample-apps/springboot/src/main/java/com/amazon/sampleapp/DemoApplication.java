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

import java.util.HashMap;
import java.util.Map;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.s3.S3Client;

@SpringBootApplication
public class DemoApplication {

  @Bean
  public Call.Factory httpClient() {
    return new OkHttpClient();
  }

  @Bean
  public S3Client s3() {
    return S3Client.builder().build();
  }

  public static void main(String[] args) {
    // listenAddress should consist host + port (e.g. 127.0.0.1:5000)
    String port;
    String host;
    String listenAddress = System.getenv("LISTEN_ADDRESS");

    if (listenAddress == null) {
      host = "127.0.0.1";
      port = "8080";
    } else {
      String[] splitAddress = listenAddress.split(":");
      host = splitAddress[0];
      port = splitAddress[1];
    }

    Map<String, Object> config =
        new HashMap<String, Object>() {
          {
            put("server.address", host);
            put("server.port", port);
          }
        };

    SpringApplication app = new SpringApplication(DemoApplication.class);
    app.setDefaultProperties(config);
    app.run(args);
  }
}
