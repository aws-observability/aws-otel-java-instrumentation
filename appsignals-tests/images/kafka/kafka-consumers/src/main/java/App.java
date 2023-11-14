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

import static spark.Spark.awaitInitialization;
import static spark.Spark.get;
import static spark.Spark.ipAddress;
import static spark.Spark.port;

import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
  public static final Logger log = LoggerFactory.getLogger(App.class);

  public static void main(String[] args) {

    String bootstrapServers = "kafkaBroker:9092";
    String topic = "kafka_topic";

    // create Producer properties
    Properties producerProperties = new Properties();
    producerProperties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
    producerProperties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    producerProperties.setProperty(
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    producerProperties.setProperty(
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    producerProperties.setProperty(ProducerConfig.MAX_BLOCK_MS_CONFIG, "15000");

    // produce and send record to kafa_topic
    KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties);
    // create a producer_record
    ProducerRecord producer_record = new ProducerRecord<>(topic, "success");
    // send data - asynchronous
    producer.send(producer_record);
    // flush data - synchronous
    producer.flush();
    // flush and close producer
    producer.close();

    // create Consumer properties
    Properties consumerProperties = new Properties();
    consumerProperties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    consumerProperties.setProperty(
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    consumerProperties.setProperty(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    consumerProperties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString());

    // spark server
    port(Integer.parseInt("8080"));
    ipAddress("0.0.0.0");

    // rest endpoints
    get(
        "/success",
        (req, res) -> {
          // create consumer
          KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties);
          consumer.subscribe(Arrays.asList(topic));
          ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(10000));

          String consumedRecord = null;
          for (ConsumerRecord<String, String> record : records) {
            if (record.value().equals("success")) {
              consumedRecord = record.value();
            }
          }
          consumer.close();
          if (consumedRecord != null && consumedRecord.equals("success")) {
            res.status(HttpStatus.OK_200);
            res.body("success");
          } else {
            log.info("consumer is unable to consumer right message");
            res.status(HttpStatus.INTERNAL_SERVER_ERROR_500);
          }
          return res.body();
        });

    awaitInitialization();
    log.info("Routes ready.");
  }
}
