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

package software.amazon.opentelemetry.appsignals.test.utils;

/***
 * Constants for attributes and metric names defined in AppSignals.
 */
public class AppSignalsConstants {
  // Metric names
  public static final String LATENCY_METRIC = "Latency";
  public static final String ERROR_METRIC = "Error";
  public static final String FAULT_METRIC = "Fault";

  // Attribute names
  public static final String AWS_LOCAL_SERVICE = "aws.local.service";
  public static final String AWS_LOCAL_OPERATION = "aws.local.operation";
  public static final String AWS_REMOTE_SERVICE = "aws.remote.service";
  public static final String AWS_REMOTE_OPERATION = "aws.remote.operation";
  public static final String AWS_REMOTE_RESOURCE_TYPE = "aws.remote.resource.type";
  public static final String AWS_REMOTE_RESOURCE_IDENTIFIER = "aws.remote.resource.identifier";
  public static final String AWS_SPAN_KIND = "aws.span.kind";
}
