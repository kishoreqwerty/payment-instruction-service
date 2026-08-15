package com.kishore.payments.intake.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.event.EventJson;
import com.kishore.payments.core.outbox.OutboxPublisher;
import com.kishore.payments.intake.AbstractIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The end-to-end claim: a real submission produces a real, readable event on
 * payments.received, correctly correlated back to the instruction, and
 * carrying none of the fields .notes/ARCHITECTURE.md and
 * {@link com.kishore.payments.core.event.InstructionReceivedEvent} agree
 * shouldn't leave the database.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class InstructionReceivedEventPublishingTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OutboxPublisher publisher;

    @Value("${payments.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Test
    void postThenConsumeFromPaymentsReceived() throws IOException {
        String original = new String(sample("valid-single-eur.xml"), StandardCharsets.UTF_8);
        // EndToEndId is Max35Text; a full UUID would overflow it once prefixed.
        byte[] body = original.replace("E2E-EUR-0001", "E2E-" + UUID.randomUUID().toString().substring(0, 8))
                .getBytes(StandardCharsets.UTF_8);

        ResponseEntity<Map> response = post(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String instructionId = (String) response.getBody().get("instructionId");
        String uetr = (String) response.getBody().get("uetr");

        publisher.publishBatch();

        ConsumerRecord<String, String> record = findRecordByKey("payments.received", instructionId, Duration.ofSeconds(20));
        assertThat(record).as("no message on payments.received with key = %s", instructionId).isNotNull();
        assertThat(record.key()).isEqualTo(instructionId);

        Map<String, Object> event = readEvent(record.value());
        assertThat(event.get("instruction_id")).isEqualTo(instructionId);
        assertThat(event.get("uetr")).isEqualTo(uetr);
        assertThat(event.get("sequence_no")).isEqualTo(1);
        assertThat(event.get("state")).isEqualTo("RECEIVED");

        // Party names and full account numbers never leave the database --
        // see the comment on InstructionReceivedEvent.
        assertThat(record.value())
                .doesNotContain("Acme Gmbh")
                .doesNotContain("Beneficiary SARL")
                .doesNotContain("DE89370400440532013000")
                .doesNotContain("FR1420041010050500013M02606");
    }

    private ConsumerRecord<String, String> findRecordByKey(String topic, String key, Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "e2e-test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (var consumer = new KafkaConsumer<String, String>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
                for (ConsumerRecord<String, String> record : records) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readEvent(String json) {
        try {
            return EventJson.MAPPER.readValue(json, Map.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private ResponseEntity<Map> post(byte[] body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        return restTemplate.postForEntity("/v1/instructions", new HttpEntity<>(body, headers), Map.class);
    }

    private static byte[] sample(String name) throws IOException {
        try (InputStream in = InstructionReceivedEventPublishingTest.class.getResourceAsStream("/samples/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Sample not found on classpath: " + name);
            }
            return in.readAllBytes();
        }
    }
}
