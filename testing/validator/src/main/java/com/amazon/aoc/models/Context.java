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

import java.util.List;
import lombok.Data;
import lombok.NonNull;

@Data
public class Context {
  @NonNull private String testingId;

  private String accountId;

  private String language;

  @NonNull private String region;

  private String availabilityZone;

  @NonNull private Boolean isCanary;

  @NonNull private Boolean isRollup;

  private String metricNamespace;

  private String appNamespace;

  private String cluster;

  private String serviceName;

  private String remoteServiceName;

  private String remoteServiceDeploymentName;

  private String endpoint;

  private String requestBody;

  private String logGroup;

  private ECSContext ecsContext;

  private CloudWatchContext cloudWatchContext;

  private EC2Context ec2Context;

  /* testcase name */
  private String testcase;

  /*
  alarm related parameters
   */
  private List<String> alarmNameList;
  private Integer alarmPullingDuration;
  private Integer alarmPullingTimes;

  /*
  mocked server parameters
   */
  private String mockedServerValidatingUrl;

  /*
  cortex parameters
   */
  private String cortexInstanceEndpoint;
}
