package com.bakdata.common_kafka_streams.util;

import static java.util.Collections.emptyList;

import java.util.function.Predicate;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.kstream.KeyValueMapper;

/**
 * Wrap a {@code KeyValueMapper} and log thrown exceptions with input key and value.
 *
 * @param <K> type of input keys
 * @param <V> type of input values
 * @param <R> type of map result
 * @see #logErrors(KeyValueMapper)
 * @see #logErrors(KeyValueMapper, Predicate)
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class ErrorLoggingFlatKeyValueMapper<K, V, R> implements KeyValueMapper<K, V, Iterable<R>> {
    private final @NonNull KeyValueMapper<? super K, ? super V, ? extends Iterable<R>> wrapped;
    private final @NonNull Predicate<Exception> errorFilter;

    /**
     * Wrap a {@code KeyValueMapper} and log thrown exceptions with input key and value. Recoverable Kafka exceptions
     * such as a schema registry timeout are forwarded and not captured.
     *
     * @see #logErrors(KeyValueMapper, Predicate)
     * @see ErrorUtil#shouldForwardError(Exception)
     */
    public static <K, V, R> KeyValueMapper<K, V, Iterable<R>> logErrors(
            final KeyValueMapper<? super K, ? super V, ? extends Iterable<R>> mapper) {
        return logErrors(mapper, ErrorUtil::shouldForwardError);
    }

    /**
     * Wrap a {@code KeyValueMapper} and log thrown exceptions with input key and value.
     * <pre>{@code
     * final KeyValueMapper<K, V, Iterable<KeyValue<KR, VR>>> mapper = ...;
     * final KStream<K, V> input = ...;
     * final KStream<KR, VR> output = input.flatMap(logErrors(mapper));
     * }
     * </pre>
     *
     * @param mapper {@code KeyValueMapper} whose exceptions should be logged
     * @param errorFilter expression that filters errors which should be thrown and not logged
     * @param <K> type of input keys
     * @param <V> type of input values
     * @param <R> type of map result
     * @return {@code KeyValueMapper}
     */
    public static <K, V, R> KeyValueMapper<K, V, Iterable<R>> logErrors(
            final KeyValueMapper<? super K, ? super V, ? extends Iterable<R>> mapper,
            final Predicate<Exception> errorFilter) {
        return new ErrorLoggingFlatKeyValueMapper<>(mapper, errorFilter);
    }

    @Override
    public Iterable<R> apply(final K key, final V value) {
        try {
            return this.wrapped.apply(key, value);
        } catch (final Exception e) {
            if (this.errorFilter.test(e)) {
                throw e;
            }
            log.error("Cannot process ('" + ErrorUtil.toString(key) + "', '" + ErrorUtil.toString(value) + "')", e);
            return emptyList();
        }
    }
}