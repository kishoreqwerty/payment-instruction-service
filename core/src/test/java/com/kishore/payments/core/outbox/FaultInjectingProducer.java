package com.kishore.payments.core.outbox;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;

/**
 * Wraps a real producer so a test can simulate two distinct failure shapes:
 * a produce that never reaches the broker at all ({@code failBeforeSendIf}),
 * and "the process died right after the broker acknowledged the send, before
 * the outbox row was marked published" ({@code crashAfterSendIf} -- the
 * delegate really produces the message first, so it genuinely lands on the
 * topic, and only then does the wrapper throw).
 */
class FaultInjectingProducer implements Producer<String, String> {

    private final Producer<String, String> delegate;
    private final Predicate<ProducerRecord<String, String>> failBeforeSendIf;
    private final Predicate<ProducerRecord<String, String>> crashAfterSendIf;

    FaultInjectingProducer(
            Producer<String, String> delegate,
            Predicate<ProducerRecord<String, String>> failBeforeSendIf,
            Predicate<ProducerRecord<String, String>> crashAfterSendIf) {
        this.delegate = delegate;
        this.failBeforeSendIf = failBeforeSendIf;
        this.crashAfterSendIf = crashAfterSendIf;
    }

    @Override
    public Future<RecordMetadata> send(ProducerRecord<String, String> record) {
        if (failBeforeSendIf.test(record)) {
            throw new RuntimeException("simulated produce failure (never reached the broker)");
        }
        try {
            RecordMetadata metadata = delegate.send(record).get();
            if (crashAfterSendIf.test(record)) {
                throw new SimulatedCrashException("simulated crash after produce, before the outbox row was marked published");
            }
            return java.util.concurrent.CompletableFuture.completedFuture(metadata);
        } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Future<RecordMetadata> send(ProducerRecord<String, String> record, Callback callback) {
        throw new UnsupportedOperationException("not used by OutboxPublisher");
    }

    static class SimulatedCrashException extends RuntimeException {
        SimulatedCrashException(String message) {
            super(message);
        }
    }

    @Override
    public void flush() {
        delegate.flush();
    }

    @Override
    public List<PartitionInfo> partitionsFor(String topic) {
        return delegate.partitionsFor(topic);
    }

    @Override
    public Map<MetricName, ? extends Metric> metrics() {
        return delegate.metrics();
    }

    @Override
    public void initTransactions() {
        delegate.initTransactions();
    }

    @Override
    public void beginTransaction() {
        delegate.beginTransaction();
    }

    @Override
    public void sendOffsetsToTransaction(Map<TopicPartition, OffsetAndMetadata> offsets, String consumerGroupId) {
        delegate.sendOffsetsToTransaction(offsets, consumerGroupId);
    }

    @Override
    public void sendOffsetsToTransaction(
            Map<TopicPartition, OffsetAndMetadata> offsets, org.apache.kafka.clients.consumer.ConsumerGroupMetadata groupMetadata) {
        delegate.sendOffsetsToTransaction(offsets, groupMetadata);
    }

    @Override
    public void commitTransaction() {
        delegate.commitTransaction();
    }

    @Override
    public void abortTransaction() {
        delegate.abortTransaction();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public void close(Duration timeout) {
        delegate.close(timeout);
    }
}
