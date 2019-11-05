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

package com.bakdata.common_kafka_streams.util;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.ValueMapper;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jooq.lambda.Seq;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@ExtendWith(SoftAssertionsExtension.class)
class ErrorCapturingValueMapperTopologyTest extends ErrorCaptureTopologyTest {

    private static final String ERROR_TOPIC = "errors";
    private static final String OUTPUT_TOPIC = "output";
    private static final String INPUT_TOPIC = "input";
    private static final Serde<String> STRING_SERDE = Serdes.String();
    private static final Serde<Long> LONG_SERDE = Serdes.Long();
    @Mock
    ValueMapper<String, Long> mapper;

    @Override
    protected void buildTopology(final StreamsBuilder builder) {
        final KStream<Integer, String> input = builder.stream(INPUT_TOPIC, Consumed.with(null, STRING_SERDE));
        final KStream<Integer, ProcessedValue<String, Long>> mapped =
                input.mapValues(ErrorCapturingValueMapper.captureErrors(this.mapper));
        mapped.flatMapValues(ProcessedValue::getValues)
                .to(OUTPUT_TOPIC, Produced.valueSerde(LONG_SERDE));
        mapped.flatMapValues(ProcessedValue::getErrors)
                .mapValues(error -> error.createDeadLetter("Description"))
                .to(ERROR_TOPIC);
    }

    @Test
    void shouldNotAllowNullMapper(final SoftAssertions softly) {
        softly.assertThatThrownBy(() -> ErrorCapturingValueMapper.captureErrors(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldForwardSchemaRegistryTimeout(final SoftAssertions softly) {
        when(this.mapper.apply("foo")).thenThrow(createSchemaRegistryTimeoutException());
        this.createTopology();
        softly.assertThatThrownBy(() -> this.topology.input()
                .withValueSerde(STRING_SERDE)
                .add(1, "foo"))
                .hasCauseInstanceOf(SerializationException.class);
        final List<ProducerRecord<Integer, Long>> records = Seq.seq(this.topology.streamOutput(OUTPUT_TOPIC)
                .withValueSerde(LONG_SERDE))
                .toList();
        softly.assertThat(records)
                .isEmpty();

        final List<ProducerRecord<Integer, DeadLetter>> errors = Seq.seq(this.topology.streamOutput(ERROR_TOPIC)
                .withValueType(DeadLetter.class))
                .toList();
        softly.assertThat(errors)
                .isEmpty();
    }

    @Test
    void shouldNotCaptureThrowable(final SoftAssertions softly) {
        final Throwable throwable = mock(Error.class);
        when(this.mapper.apply("foo")).thenThrow(throwable);
        this.createTopology();
        softly.assertThatThrownBy(() -> this.topology.input()
                .withValueSerde(STRING_SERDE)
                .add(1, "foo"))
                .isEqualTo(throwable);
    }

    @Test
    void shouldCaptureValueMapperError(final SoftAssertions softly) {
        doThrow(new RuntimeException("Cannot process")).when(this.mapper).apply("foo");
        doReturn(2L).when(this.mapper).apply("bar");
        this.createTopology();
        this.topology.input()
                .withValueSerde(STRING_SERDE)
                .add(1, "foo")
                .add(2, "bar");
        final List<ProducerRecord<Integer, Long>> records = Seq.seq(this.topology.streamOutput(OUTPUT_TOPIC)
                .withValueSerde(LONG_SERDE))
                .toList();
        softly.assertThat(records)
                .hasSize(1)
                .first()
                .isNotNull()
                .satisfies(record -> softly.assertThat(record.key()).isEqualTo(2))
                .extracting(ProducerRecord::value)
                .isInstanceOf(Long.class)
                .satisfies(value -> softly.assertThat(value).isEqualTo(2L));
        final List<ProducerRecord<Integer, DeadLetter>> errors = Seq.seq(this.topology.streamOutput(ERROR_TOPIC)
                .withValueType(DeadLetter.class))
                .toList();
        softly.assertThat(errors)
                .hasSize(1)
                .first()
                .isNotNull()
                .satisfies(record -> softly.assertThat(record.key()).isEqualTo(1))
                .extracting(ProducerRecord::value)
                .isInstanceOf(DeadLetter.class)
                .satisfies(deadLetter -> {
                    softly.assertThat(deadLetter.getInputValue()).isEqualTo("foo");
                    softly.assertThat(deadLetter.getDescription()).isEqualTo("Description");
                    softly.assertThat(deadLetter.getCause().getMessage()).isEqualTo("Cannot process");
                    softly.assertThat(deadLetter.getCause().getStackTrace()).isNotNull();
                });
    }

    @Test
    void shouldHandleNullInput(final SoftAssertions softly) {
        when(this.mapper.apply(null)).thenReturn(2L);
        this.createTopology();
        this.topology.input()
                .withValueSerde(STRING_SERDE)
                .add(2, null);
        final List<ProducerRecord<Integer, Long>> records = Seq.seq(this.topology.streamOutput(OUTPUT_TOPIC)
                .withValueSerde(LONG_SERDE))
                .toList();
        softly.assertThat(records)
                .hasSize(1)
                .first()
                .isNotNull()
                .satisfies(record -> softly.assertThat(record.key()).isEqualTo(2))
                .extracting(ProducerRecord::value)
                .isInstanceOf(Long.class)
                .satisfies(value -> softly.assertThat(value).isEqualTo(2L));
        final List<ProducerRecord<Integer, DeadLetter>> errors = Seq.seq(this.topology.streamOutput(ERROR_TOPIC)
                .withValueType(DeadLetter.class))
                .toList();
        softly.assertThat(errors)
                .isEmpty();
    }

    @Test
    void shouldHandleErrorOnNullInput(final SoftAssertions softly) {
        when(this.mapper.apply(null)).thenThrow(new RuntimeException("Cannot process"));
        this.createTopology();
        this.topology.input()
                .withValueSerde(STRING_SERDE)
                .add(null, null);
        final List<ProducerRecord<Integer, Long>> records = Seq.seq(this.topology.streamOutput(OUTPUT_TOPIC)
                .withValueSerde(LONG_SERDE))
                .toList();
        softly.assertThat(records)
                .isEmpty();
        final List<ProducerRecord<Integer, DeadLetter>> errors = Seq.seq(this.topology.streamOutput(ERROR_TOPIC)
                .withValueType(DeadLetter.class))
                .toList();
        softly.assertThat(errors)
                .hasSize(1)
                .first()
                .isNotNull()
                .satisfies(record -> softly.assertThat(record.key()).isNull())
                .extracting(ProducerRecord::value)
                .isInstanceOf(DeadLetter.class)
                .satisfies(deadLetter -> {
                    softly.assertThat(deadLetter.getInputValue()).isNull();
                    softly.assertThat(deadLetter.getDescription()).isEqualTo("Description");
                    softly.assertThat(deadLetter.getCause().getMessage()).isEqualTo("Cannot process");
                    softly.assertThat(deadLetter.getCause().getStackTrace()).isNotNull();
                });
    }

    @Test
    void shouldHandleNullValue(final SoftAssertions softly) {
        when(this.mapper.apply("bar")).thenReturn(null);
        this.createTopology();
        this.topology.input()
                .withValueSerde(STRING_SERDE)
                .add(2, "bar");
        final List<ProducerRecord<Integer, Long>> records = Seq.seq(this.topology.streamOutput(OUTPUT_TOPIC)
                .withValueSerde(LONG_SERDE))
                .toList();
        softly.assertThat(records)
                .hasSize(1)
                .first()
                .isNotNull()
                .satisfies(record -> softly.assertThat(record.key()).isEqualTo(2))
                .extracting(ProducerRecord::value)
                .satisfies(value -> softly.assertThat(value).isNull());
        final List<ProducerRecord<Integer, DeadLetter>> errors = Seq.seq(this.topology.streamOutput(ERROR_TOPIC)
                .withValueType(DeadLetter.class))
                .toList();
        softly.assertThat(errors)
                .isEmpty();
    }

}
