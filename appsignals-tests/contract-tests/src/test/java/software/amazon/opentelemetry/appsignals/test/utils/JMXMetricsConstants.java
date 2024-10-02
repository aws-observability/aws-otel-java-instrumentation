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

import java.util.Set;

public class JMXMetricsConstants {
  // JVM Metrics
  public static final String JVM_CLASS_LOADED = "jvm.classes.loaded";
  public static final String JVM_GC_COUNT = "jvm.gc.collections.count";
  public static final String JVM_GC_METRIC = "jvm.gc.collections.elapsed";
  public static final String JVM_HEAP_INIT = "jvm.memory.heap.init";
  public static final String JVM_HEAP_USED = "jvm.memory.heap.used";
  public static final String JVM_HEAP_COMMITTED = "jvm.memory.heap.committed";
  public static final String JVM_HEAP_MAX = "jvm.memory.heap.max";
  public static final String JVM_NON_HEAP_INIT = "jvm.memory.nonheap.init";
  public static final String JVM_NON_HEAP_USED = "jvm.memory.nonheap.used";
  public static final String JVM_NON_HEAP_COMMITTED = "jvm.memory.nonheap.committed";
  public static final String JVM_NON_HEAP_MAX = "jvm.memory.nonheap.max";
  public static final String JVM_POOL_INIT = "jvm.memory.pool.init";
  public static final String JVM_POOL_USED = "jvm.memory.pool.used";
  public static final String JVM_POOL_COMMITTED = "jvm.memory.pool.committed";
  public static final String JVM_POOL_MAX = "jvm.memory.pool.max";
  public static final String JVM_THREADS_COUNT = "jvm.threads.count";
  public static final String JVM_DAEMON_THREADS_COUNT = "jvm.daemon_threads.count";
  public static final String JVM_SYSTEM_SWAP_TOTAL = "jvm.system.swap.space.total";
  public static final String JVM_SYSTEM_SWAP_FREE = "jvm.system.swap.space.free";
  public static final String JVM_SYSTEM_MEM_TOTAL = "jvm.system.physical.memory.total";
  public static final String JVM_SYSTEM_MEM_FREE = "jvm.system.physical.memory.free";
  public static final String JVM_SYSTEM_AVAILABLE_PROCESSORS = "jvm.system.available.processors";
  public static final String JVM_SYSTEM_CPU_UTILIZATION = "jvm.system.cpu.utilization";
  public static final String JVM_CPU_UTILIZATION = "jvm.cpu.recent_utilization";
  public static final String JVM_FILE_DESCRIPTORS = "jvm.open_file_descriptor.count";

  public static final Set<String> JVM_METRICS_SET =
      Set.of(
          JVM_CLASS_LOADED,
          JVM_GC_COUNT,
          JVM_GC_METRIC,
          JVM_HEAP_INIT,
          JVM_HEAP_USED,
          JVM_HEAP_COMMITTED,
          JVM_HEAP_MAX,
          JVM_NON_HEAP_INIT,
          JVM_NON_HEAP_USED,
          JVM_NON_HEAP_COMMITTED,
          JVM_NON_HEAP_MAX,
          JVM_POOL_INIT,
          JVM_POOL_USED,
          JVM_POOL_COMMITTED,
          JVM_POOL_MAX,
          JVM_THREADS_COUNT,
          JVM_DAEMON_THREADS_COUNT,
          JVM_SYSTEM_SWAP_TOTAL,
          JVM_SYSTEM_SWAP_FREE,
          JVM_SYSTEM_MEM_TOTAL,
          JVM_SYSTEM_MEM_FREE,
          JVM_SYSTEM_AVAILABLE_PROCESSORS,
          JVM_SYSTEM_CPU_UTILIZATION,
          JVM_CPU_UTILIZATION,
          JVM_FILE_DESCRIPTORS);

  // Tomcat Metrics
  public static final String TOMCAT_SESSION = "tomcat.sessions";
  public static final String TOMCAT_REJECTED_SESSION = "tomcat.rejected_sessions";
  public static final String TOMCAT_ERRORS = "tomcat.errors";
  public static final String TOMCAT_REQUEST_COUNT = "tomcat.request_count";
  public static final String TOMCAT_MAX_TIME = "tomcat.max_time";
  public static final String TOMCAT_PROCESSING_TIME = "tomcat.processing_time";
  public static final String TOMCAT_TRAFFIC = "tomcat.traffic";
  public static final String TOMCAT_THREADS = "tomcat.threads";

  public static final Set<String> TOMCAT_METRICS_SET =
      Set.of(
          TOMCAT_SESSION,
          TOMCAT_REJECTED_SESSION,
          TOMCAT_ERRORS,
          TOMCAT_REQUEST_COUNT,
          TOMCAT_MAX_TIME,
          TOMCAT_PROCESSING_TIME,
          TOMCAT_TRAFFIC,
          TOMCAT_THREADS);

  // Kafka Metrics
  public static final String KAFKA_MESSAGE_COUNT = "kafka.message.count";
  public static final String KAFKA_REQUEST_COUNT = "kafka.request.count";
  public static final String KAFKA_REQUEST_FAILED = "kafka.request.failed";
  public static final String KAFKA_REQUEST_TIME_TOTAL = "kafka.request.time.total";
  public static final String KAFKA_REQUEST_TIME_50P = "kafka.request.time.50p";
  public static final String KAFKA_REQUEST_TIME_99P = "kafka.request.time.99p";
  public static final String KAFKA_REQUEST_TIME_AVG = "kafka.request.time.avg";
  public static final String KAFKA_NETWORK_IO = "kafka.network.io";
  public static final String KAFKA_PURGATORY_SIZE = "kafka.purgatory.size";
  public static final String KAFKA_PARTITION_COUNT = "kafka.partition.count";
  public static final String KAFKA_PARTITION_OFFLINE = "kafka.partition.offline";
  public static final String KAFKA_PARTITION_UNDER_REPLICATED = "kafka.partition.under_replicated";
  public static final String KAFKA_ISR_OPERATION_COUNT = "kafka.isr.operation.count";
  public static final String KAFKA_MAX_LAG = "kafka.max.lag";
  public static final String KAFKA_CONTROLLER_ACTIVE_COUNT = "kafka.controller.active.count";
  public static final String KAFKA_LEADER_ELECTION_RATE = "kafka.leader.election.rate";
  public static final String KAFKA_UNCLEAN_ELECTION_RATE = "kafka.unclean.election.rate";
  public static final String KAFKA_REQUEST_QUEUE = "kafka.request.queue";
  public static final String KAFKA_LOGS_FLUSH_TIME_COUNT = "kafka.logs.flush.time.count";
  public static final String KAFKA_LOGS_FLUSH_TIME_MEDIAN = "kafka.logs.flush.time.median";
  public static final String KAFKA_LOGS_FLUSH_TIME_99P = "kafka.logs.flush.time.99p";

  public static final Set<String> KAFKA_METRICS_SET =
      Set.of(
          KAFKA_MESSAGE_COUNT,
          KAFKA_REQUEST_COUNT,
          KAFKA_REQUEST_FAILED,
          KAFKA_REQUEST_TIME_TOTAL,
          KAFKA_REQUEST_TIME_50P,
          KAFKA_REQUEST_TIME_99P,
          KAFKA_REQUEST_TIME_AVG,
          KAFKA_NETWORK_IO,
          KAFKA_PURGATORY_SIZE,
          KAFKA_PARTITION_COUNT,
          KAFKA_PARTITION_OFFLINE,
          KAFKA_PARTITION_UNDER_REPLICATED,
          KAFKA_ISR_OPERATION_COUNT,
          KAFKA_MAX_LAG,
          KAFKA_CONTROLLER_ACTIVE_COUNT,
          // TODO: Add test case for leader election.
          //          KAFKA_LEADER_ELECTION_RATE,
          //          KAFKA_UNCLEAN_ELECTION_RATE,
          KAFKA_REQUEST_QUEUE,
          KAFKA_LOGS_FLUSH_TIME_COUNT,
          KAFKA_LOGS_FLUSH_TIME_MEDIAN,
          KAFKA_LOGS_FLUSH_TIME_99P);

  // Kafka Consumer Metrics
  public static final String KAFKA_CONSUMER_FETCH_RATE = "kafka.consumer.fetch-rate";
  public static final String KAFKA_CONSUMER_RECORDS_LAG_MAX = "kafka.consumer.records-lag-max";
  public static final String KAFKA_CONSUMER_TOTAL_BYTES_CONSUMED_RATE =
      "kafka.consumer.total.bytes-consumed-rate";
  public static final String KAFKA_CONSUMER_TOTAL_FETCH_SIZE_AVG =
      "kafka.consumer.total.fetch-size-avg";
  public static final String KAFKA_CONSUMER_TOTAL_RECORDS_CONSUMED_RATE =
      "kafka.consumer.total.records-consumed-rate";
  public static final String KAFKA_CONSUMER_BYTES_CONSUMED_RATE =
      "kafka.consumer.bytes-consumed-rate";
  public static final String KAFKA_CONSUMER_FETCH_SIZE_AVG = "kafka.consumer.fetch-size-avg";
  public static final String KAFKA_CONSUMER_RECORDS_CONSUMED_RATE =
      "kafka.consumer.records-consumed-rate";

  public static final Set<String> KAFKA_CONSUMER_METRICS_SET =
      Set.of(
          KAFKA_CONSUMER_FETCH_RATE,
          KAFKA_CONSUMER_RECORDS_LAG_MAX,
          KAFKA_CONSUMER_TOTAL_BYTES_CONSUMED_RATE,
          KAFKA_CONSUMER_TOTAL_FETCH_SIZE_AVG,
          KAFKA_CONSUMER_TOTAL_RECORDS_CONSUMED_RATE,
          KAFKA_CONSUMER_BYTES_CONSUMED_RATE,
          KAFKA_CONSUMER_FETCH_SIZE_AVG,
          KAFKA_CONSUMER_RECORDS_CONSUMED_RATE);

  // Kafka Producer Metrics
  public static final String KAFKA_PRODUCER_IO_WAIT_TIME_NS_AVG =
      "kafka.producer.io-wait-time-ns-avg";
  public static final String KAFKA_PRODUCER_OUTGOING_BYTE_RATE =
      "kafka.producer.outgoing-byte-rate";
  public static final String KAFKA_PRODUCER_REQUEST_LATENCY_AVG =
      "kafka.producer.request-latency-avg";
  public static final String KAFKA_PRODUCER_REQUEST_RATE = "kafka-producer.request-rate";
  public static final String KAFKA_PRODUCER_RESPONSE_RATE = "kafka.producer.response-rate";
  public static final String KAFKA_PRODUCER_BYTE_RATE = "kafka.producer.byte-rate";
  public static final String KAFKA_PRODUCER_COMPRESSION_RATE = "kafka.producer.compression-rate";
  public static final String KAFKA_PRODUCER_RECORD_ERROR_RATE = "kafka.producer.record-error-rate";
  public static final String KAFKA_PRODUCER_RECORD_RETRY_RATE = "kafka.producer.record-retry-rate";
  public static final String KAFKA_PRODUCER_RECORD_SEND_RATE = "kafka.producer.record-send-rate";

  public static final Set<String> KAFKA_PRODUCER_METRICS_SET =
      Set.of(
          KAFKA_PRODUCER_IO_WAIT_TIME_NS_AVG,
          KAFKA_PRODUCER_OUTGOING_BYTE_RATE,
          KAFKA_PRODUCER_REQUEST_LATENCY_AVG,
          KAFKA_PRODUCER_REQUEST_RATE,
          KAFKA_PRODUCER_RESPONSE_RATE,
          KAFKA_PRODUCER_BYTE_RATE,
          KAFKA_PRODUCER_COMPRESSION_RATE,
          KAFKA_PRODUCER_RECORD_ERROR_RATE,
          KAFKA_PRODUCER_RECORD_RETRY_RATE,
          KAFKA_PRODUCER_RECORD_SEND_RATE);
}
