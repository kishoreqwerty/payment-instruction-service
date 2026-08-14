package com.kishore.payments.intake.rawmessage;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.intake.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
class RawMessageServiceTest extends AbstractIntegrationTest {

    @Autowired
    private RawMessageService service;

    @Autowired
    private RawMessageRepository repository;

    @Test
    void persistsRawBytesWithMatchingSha256AndSchemaValidFalseInitially() throws Exception {
        byte[] payload = "<Document>not yet parsed</Document>".getBytes(StandardCharsets.UTF_8);

        RawMessageEntity saved = service.persist(payload, "REST", "client-1", "application/xml");

        byte[] expectedSha256 = MessageDigest.getInstance("SHA-256").digest(payload);
        assertThat(saved.getPayloadSha256()).isEqualTo(expectedSha256);
        assertThat(saved.getPayload()).isEqualTo(payload);
        assertThat(saved.isSchemaValid()).isFalse();
        assertThat(saved.getSourceChannel()).isEqualTo("REST");
        assertThat(saved.getSourceIdentifier()).isEqualTo("client-1");
        assertThat(saved.getContentType()).isEqualTo("application/xml");

        RawMessageEntity reloaded = repository.findById(saved.getRawMessageId()).orElseThrow();
        assertThat(reloaded.getPayloadSha256()).isEqualTo(expectedSha256);
        assertThat(reloaded.getPayload()).isEqualTo(payload);
    }

    @Test
    void differentPayloadsProduceDifferentDigestsAndDistinctRows() {
        RawMessageEntity a = service.persist("A".repeat(20).getBytes(StandardCharsets.UTF_8), "REST", null, "application/xml");
        RawMessageEntity b = service.persist("B".repeat(20).getBytes(StandardCharsets.UTF_8), "REST", null, "application/xml");

        assertThat(a.getPayloadSha256()).isNotEqualTo(b.getPayloadSha256());
        assertThat(a.getRawMessageId()).isNotEqualTo(b.getRawMessageId());
    }

    @Test
    void malformedPayloadIsPersistedJustLikeAWellFormedOne() {
        byte[] malformed = "<Document><Unclosed>".getBytes(StandardCharsets.UTF_8);

        RawMessageEntity saved = service.persist(malformed, "REST", null, "application/xml");

        assertThat(repository.findById(saved.getRawMessageId())).isPresent();
    }
}
