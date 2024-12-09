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

import static spark.Spark.awaitInitialization;
import static spark.Spark.get;
import static spark.Spark.ipAddress;
import static spark.Spark.port;

import java.util.Properties;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
  public static final Logger log = LoggerFactory.getLogger(App.class);

  public static void main(String[] args) {
    port(Integer.parseInt("8080"));
    ipAddress("0.0.0.0");

    // create Producer properties
    Properties properties = new Properties();
    properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
    properties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafkaBroker:9092");
    properties.setProperty(
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    properties.setProperty(
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    properties.setProperty(ProducerConfig.MAX_BLOCK_MS_CONFIG, "10000");

    // create the producer
    // initialized and reused to expose the kafka producer beans for JMX
    KafkaProducer<String, String> producer = new KafkaProducer<>(properties);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  log.info("Shutting down Kafka producer...");
                  producer.close();
                }));

    // rest endpoints
    get(
        "/success",
        (req, res) -> {
          // create a record
          ProducerRecord record = new ProducerRecord<>("kafka_topic", "success");
          // send data - asynchronous
          producer.send(record);
          // flush data - synchronous
          producer.flush();

          res.status(HttpStatus.OK_200);
          res.body("success");
          return res.body();
        });
    get(
        "/fault",
        (req, res) -> {
          // create a record & send data to a topic that does not exist- asynchronous
          ProducerRecord producerRecord = new ProducerRecord<>("fault_do_not_exist", "fault");
          producer.send(
              producerRecord,
              new Callback() {
                public void onCompletion(RecordMetadata recordMetadata, Exception e) {
                  if (e == null) {
                    log.info(
                        "Successfully received the details as: \n"
                            + "Topic:"
                            + recordMetadata.topic());
                  } else {
                    log.error("Can't produce, getting error", e);
                    res.status(HttpStatus.INTERNAL_SERVER_ERROR_500);
                  }
                }
              });
          // flush data - synchronous
          producer.flush();
          res.body("fault");
          return res.body();
        });

    awaitInitialization();
    log.info("Routes ready.");
  }
}
