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

package com.amazon.aoc.models;

import lombok.Data;

@Data
public class CloudWatchContext {
  private String clusterName;

  private App appMesh;
  private App nginx;
  private App jmx;
  private App haproxy;
  private App memcached;

  public void setAppMesh(App appMesh) {
    appMesh.setName("appMesh");
    this.appMesh = appMesh;
  }

  public void setNginx(App nginx) {
    nginx.setName("nginx");
    this.nginx = nginx;
  }

  public void setJmx(App jmx) {
    jmx.setName("jmx");
    this.jmx = jmx;
  }

  public void setHaproxy(App haproxy) {
    haproxy.setName("haproxy");
    this.haproxy = haproxy;
  }

  public void setMemcached(App memcached) {
    memcached.setName("memcached");
    this.memcached = memcached;
  }

  @Data
  public static class App {
    private String name;
    private String namespace;
    private String job;
    // For ECS
    private String[] taskDefinitionFamilies;
    private String serviceName;
  }
}
