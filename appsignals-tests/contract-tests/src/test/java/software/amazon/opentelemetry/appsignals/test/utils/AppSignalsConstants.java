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
  public static final String AWS_REMOTE_DB_USER = "aws.remote.db.user";

  // JVM Metrics
  public static final String JVM_GC_METRIC = "jvm.gc.collections.elapsed";
  public static final String JVM_GC_COUNT = "jvm.gc.collections.count";
  public static final String JVM_HEAP_USED = "jvm.memory.heap.used";
  public static final String JVM_NON_HEAP_USED = "jvm.memory.nonheap.used";
  public static final String JVM_AFTER_GC = "jvm.memory.pool.used_after_last_gc";
  public static final String JVM_POOL_USED = "jvm.memory.pool.used";
  public static final String JVM_THREAD_COUNT = "jvm.threads.count";
  public static final String JVM_CLASS_LOADED = "jvm.classes.loaded";
  public static final String JVM_CPU_TIME = "jvm.cpu.time";
  public static final String JVM_CPU_UTILIZATION = "jvm.cpu.recent_utilization";
}
