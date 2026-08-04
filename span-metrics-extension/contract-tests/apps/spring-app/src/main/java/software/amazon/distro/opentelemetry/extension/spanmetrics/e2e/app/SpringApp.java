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

package software.amazon.distro.opentelemetry.extension.spanmetrics.e2e.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot web application with the OpenTelemetry Spring Boot starter and the span-metrics
 * extension jar on the classpath. The starter auto-instruments the web layer and JPA calls; the
 * extension's Spring auto-configuration binds the OpenTelemetry bean so span metrics are produced.
 */
@SpringBootApplication
public class SpringApp {

  public static void main(String[] args) {
    SpringApplication.run(SpringApp.class, args);
  }
}
