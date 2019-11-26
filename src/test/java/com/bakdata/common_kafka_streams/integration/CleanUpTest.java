/*
 * MIT License
 *
 * Copyright (c) 2019 bakdata
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.bakdata.common_kafka_streams.integration;


import static net.mguenther.kafka.junit.EmbeddedKafkaCluster.provisionWith;
import static net.mguenther.kafka.junit.EmbeddedKafkaClusterConfig.useDefaults;

import com.bakdata.common.kafka.streams.TestRecord;
import com.bakdata.common_kafka_streams.KafkaStreamsApplication;
import com.bakdata.common_kafka_streams.test_applications.ComplexTopologyApplication;
import com.bakdata.common_kafka_streams.test_applications.MirrorKeyWithAvro;
import com.bakdata.common_kafka_streams.test_applications.MirrorValueWithAvro;
import com.bakdata.common_kafka_streams.test_applications.WordCount;
import com.bakdata.schemaregistrymock.junit5.SchemaRegistryMockExtension;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import net.mguenther.kafka.junit.EmbeddedKafkaCluster;
import net.mguenther.kafka.junit.KeyValue;
import net.mguenther.kafka.junit.ReadKeyValues;
import net.mguenther.kafka.junit.SendKeyValuesTransactional;
import net.mguenther.kafka.junit.SendValuesTransactional;
import net.mguenther.kafka.junit.TopicConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsConfig;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

@Slf4j
@ExtendWith(SoftAssertionsExtension.class)
class CleanUpTest {
    private static final int TIMEOUT_SECONDS = 10;
    @RegisterExtension
    final SchemaRegistryMockExtension schemaRegistryMockExtension = new SchemaRegistryMockExtension();
    private EmbeddedKafkaCluster kafkaCluster;
    private KafkaStreamsApplication app = null;

    @BeforeEach
    void setup() throws InterruptedException {
        this.kafkaCluster = provisionWith(useDefaults());
        this.kafkaCluster.start();
        Thread.sleep(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
    }

    @AfterEach
    void teardown() throws InterruptedException {
        if (this.app != null) {
            this.app.close();
            this.app.getStreams().cleanUp();
            this.app = null;
        }

        Thread.sleep(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        this.kafkaCluster.stop();
    }

    @Test
    void shouldDeleteTopic(final SoftAssertions softly) throws InterruptedException {
        this.app = this.createWordCountApplication();
        final SendValuesTransactional<String> sendRequest = SendValuesTransactional
                .inTransaction(this.app.getInputTopic(), List.of("blub", "bla", "blub"))
                .useDefaults();
        this.kafkaCluster.send(sendRequest);
        Thread.sleep(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

        final List<KeyValue<String, Long>> expectedValues =
                List.of(new KeyValue<>("blub", 1L),
                        new KeyValue<>("bla", 1L),
                        new KeyValue<>("blub", 2L)
                );

        this.runAndAssertContent(softly, expectedValues, "WordCount contains all elements after first run");

        this.runCleanUpWithDeletion();

        softly.assertThat(this.kafkaCluster.exists(this.app.getOutputTopic()))
                .as("Output topic is deleted")
                .isFalse();

        softly.assertThat(this.kafkaCluster.exists(this.app.getErrorTopic()))
                .as("Error topic is deleted")
                .isFalse();
    }

    @Test
    void shouldDeleteState(final SoftAssertions softly) throws InterruptedException {
        this.app = this.createWordCountApplication();
        final SendValuesTransactional<String> sendRequest = SendValuesTransactional
                .inTransaction(this.app.getInputTopic(), List.of("blub", "bla", "blub"))
                .useDefaults();
        this.kafkaCluster.send(sendRequest);
        Thread.sleep(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

        final List<KeyValue<String, Long>> expectedValues = List.of(
                new KeyValue<>("blub", 1L),
                new KeyValue<>("bla", 1L),
                new KeyValue<>("blub", 2L)
        );

        this.runAndAssertContent(softly, expectedValues, "All entries are once in the input topic after the 1st run");
        Thread.sleep(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        this.runCleanUp();

        final List<KeyValue<String, Long>> entriesTwice = expectedValues.stream()
                .flatMap(entry -> Stream.of(entry, entry))
                .collect(Collectors.toList());
        this.runAndAssertContent(softly, entriesTwice, "All entries are twice in the input topic after the 2nd run");
    }

    @Test
    void shouldReprocessAlreadySeenRecords(final SoftAssertions softly) throws InterruptedException {
        this.app = this.createWordCountApplication();
        final SendValuesTransactional<String> sendRequest =
                SendValuesTransactional.inTransaction(this.app.getInputTopic(),
                        Arrays.asList("a", "b", "c")).useDefaults();
        this.kafkaCluster.send(sendRequest);

        this.runAndAssertSize(softly, 3);
        this.runAndAssertSize(softly, 3);

        // Wait until all stream application are completely stopped before triggering cleanup
        Thread.sleep(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        this.runCleanUp();
        this.runAndAssertSize(softly, 6);
    }

    @Test
    void shouldDeleteValueSchema(final SoftAssertions softly)
            throws InterruptedException, IOException, RestClientException {
        this.app = this.createMirrorValueApplication();
        final SchemaRegistryClient client = this.schemaRegistryMockExtension.getSchemaRegistryClient();
        final TestRecord testRecord = TestRecord.newBuilder().setContent("key 1").build();
        final SendValuesTransactional<TestRecord> sendRequest = SendValuesTransactional
                .inTransaction(this.app.getInputTopic(), Collections.singletonList(testRecord))
                .with("schema.registry.url", this.schemaRegistryMockExtension.getUrl())
                .with(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class.getName())
                .build();

        this.kafkaCluster.send(sendRequest);
        this.runApp();
        Thread.sleep(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        softly.assertThat(client.getAllSubjects())
                .contains(this.app.getOutputTopic() + "-value", this.app.getInputTopic() + "-value");
        this.runCleanUpWithDeletion();
        softly.assertThat(client.getAllSubjects())
                .doesNotContain(this.app.getOutputTopic() + "-value")
                .contains(this.app.getInputTopic() + "-value");
    }

    @Test
    void shouldDeleteKeySchema(final SoftAssertions softly)
            throws InterruptedException, IOException, RestClientException {
        this.app = this.createMirrorKeyApplication();
        final SchemaRegistryClient client = this.schemaRegistryMockExtension.getSchemaRegistryClient();
        this.app.setStreamsConfig(Map.of(
                StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, SpecificAvroSerde.class.getName()
        ));
        final TestRecord testRecord = TestRecord.newBuilder().setContent("key 1").build();
        final SendKeyValuesTransactional<TestRecord, String> sendRequest = SendKeyValuesTransactional
                .inTransaction(this.app.getInputTopic(), Collections.singletonList(new KeyValue<>(testRecord, "val")))
                .with("schema.registry.url", this.schemaRegistryMockExtension.getUrl())
                .with(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class.getName())
                .build();

        this.kafkaCluster.send(sendRequest);
        this.runApp();
        Thread.sleep(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        softly.assertThat(client.getAllSubjects())
                .contains(this.app.getOutputTopic() + "-key", this.app.getInputTopic() + "-key");
        this.runCleanUpWithDeletion();
        softly.assertThat(client.getAllSubjects())
                .doesNotContain(this.app.getOutputTopic() + "-key")
                .contains(this.app.getInputTopic() + "-key");
    }

    @Test
    void shouldDeleteSchemaOfInternalTopic(final SoftAssertions softly)
            throws InterruptedException, IOException, RestClientException {
        this.app = this.createBuffer();
        final String manualTopic = ComplexTopologyApplication.THROUGH_TOPIC;
        final String automaticTopic =
                this.app.getUniqueAppId() + "-KSTREAM-AGGREGATE-STATE-STORE-0000000008-repartition";

        final SchemaRegistryClient client = this.schemaRegistryMockExtension.getSchemaRegistryClient();
        final TestRecord testRecord = TestRecord.newBuilder().setContent("key 1").build();
        final SendKeyValuesTransactional<String, TestRecord> sendRequest = SendKeyValuesTransactional
                .inTransaction(this.app.getInputTopic(), Collections.singletonList(new KeyValue<>("key 1", testRecord)))
                .with("schema.registry.url", this.schemaRegistryMockExtension.getUrl())
                .with(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName())
                .with(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class.getName())
                .build();

        this.kafkaCluster.send(sendRequest);
        this.runApp();

        softly.assertThat(client.getAllSubjects())
                .contains(automaticTopic + "-value", manualTopic + "-value", this.app.getInputTopic() + "-value");

        this.runCleanUpWithDeletion();

        softly.assertThat(client.getAllSubjects())
                .doesNotContain(automaticTopic + "-value", manualTopic + "-value")
                .contains(this.app.getInputTopic() + "-value");
    }

    @Test
    void shouldDeleteSchemaOfBackingTopic(final SoftAssertions softly)
            throws IOException, RestClientException, InterruptedException {
        this.app = this.createBuffer();

        final String backingTopic =
                this.app.getUniqueAppId() + "-KSTREAM-REDUCE-STATE-STORE-0000000003-changelog-value";

        final SchemaRegistryClient client = this.schemaRegistryMockExtension.getSchemaRegistryClient();
        final TestRecord testRecord = TestRecord.newBuilder().setContent("key 1").build();
        final SendKeyValuesTransactional<String, TestRecord> sendRequest = SendKeyValuesTransactional
                .inTransaction(this.app.getInputTopic(), Collections.singletonList(new KeyValue<>("key 1", testRecord)))
                .with("schema.registry.url", this.schemaRegistryMockExtension.getUrl())
                .with(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName())
                .with(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class.getName())
                .build();

        this.kafkaCluster.send(sendRequest);
        this.runApp();

        softly.assertThat(client.getAllSubjects()).contains(backingTopic, this.app.getInputTopic() + "-value");

        this.runCleanUpWithDeletion();

        softly.assertThat(client.getAllSubjects())
                .doesNotContain(backingTopic)
                .contains(this.app.getInputTopic() + "-value");
    }

    private List<KeyValue<String, Long>> readOutputTopic(final String outputTopic) throws InterruptedException {
        final ReadKeyValues<String, Long> readRequest = ReadKeyValues.from(outputTopic, Long.class)
                .with(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, LongDeserializer.class).build();
        return this.kafkaCluster.read(readRequest);
    }

    private void runCleanUp() {
        this.app.setCleanUp(true);
        this.app.run();
        this.app.setCleanUp(false);
    }

    private void runCleanUpWithDeletion() {
        this.app.setDeleteOutputTopic(true);
        this.runCleanUp();
        this.app.setDeleteOutputTopic(false);
    }

    private void runAndAssertContent(final SoftAssertions softly, final Iterable<KeyValue<String, Long>> expectedValues,
            final String description)
            throws InterruptedException {
        this.runApp();

        final List<KeyValue<String, Long>> output = this.readOutputTopic(this.app.getOutputTopic());
        softly.assertThat(output)
                .as(description)
                .containsExactlyInAnyOrderElementsOf(expectedValues);
    }

    private void runAndAssertSize(final SoftAssertions softly, final int expectedMessageCount)
            throws InterruptedException {
        this.runApp();
        final List<KeyValue<String, Long>> records = this.readOutputTopic(this.app.getOutputTopic());
        softly.assertThat(records).hasSize(expectedMessageCount);
    }

    private void runApp() throws InterruptedException {
        this.app.run();
        // Wait until stream application has consumed all data
        Thread.sleep(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        this.app.close();

    }

    private KafkaStreamsApplication createWordCountApplication() {
        return this.setupApp(new WordCount(), "word_input", "word_output", "word_error");
    }

    private KafkaStreamsApplication createMirrorValueApplication() {
        return this.setupApp(new MirrorValueWithAvro(), "input", "output", "key_error");
    }

    private KafkaStreamsApplication createMirrorKeyApplication() {
        return this.setupApp(new MirrorKeyWithAvro(), "input", "output", "value_error");
    }

    private KafkaStreamsApplication createBuffer() {
        this.kafkaCluster.createTopic(TopicConfig.forTopic("internal-topic").useDefaults());
        return this.setupApp(new ComplexTopologyApplication(), "input", "output", "value_error");
    }

    private KafkaStreamsApplication setupApp(final KafkaStreamsApplication application, final String inputTopicName,
            final String outputTopicName, final String errorTopicName) {
        application.setSchemaRegistryUrl(this.schemaRegistryMockExtension.getUrl());
        application.setInputTopics(List.of(inputTopicName));
        application.setOutputTopic(outputTopicName);
        application.setErrorTopic(errorTopicName);
        application.setBrokers(this.kafkaCluster.getBrokerList());
        application.setProductive(false);
        application.setStreamsConfig(Map.of(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, "0"));
        return application;
    }
}
