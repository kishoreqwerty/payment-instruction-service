package com.kishore.payments.intake.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.intake.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Controller-level concurrency, distinct from
 * {@code InstructionStateWriterTest}'s state-transition-level race: these
 * tests fire real HTTP requests at the running endpoint and let the
 * uq_reference constraint arbitrate, per the "let the database decide, don't
 * pre-check" design in .notes/reports/PHASE-2-REPORT.md.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class IntakeControllerConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @RepeatedTest(5)
    void concurrentIdenticalSubmissionsProduceExactlyOneInstructionAndNo5xx() throws Exception {
        String endToEndId = uniqueEndToEndId("CI");
        byte[] body = pain001(endToEndId, "FR1420041010050500013M02606");
        int n = 16;

        List<ResponseEntity<Map>> responses = fireConcurrently(n, () -> post(body));

        assertThat(responses).noneMatch(r -> r.getStatusCode().is5xxServerError());
        long accepted = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.ACCEPTED).count();
        long duplicates = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).count();
        assertThat(accepted).as("exactly one submission should create the instruction").isEqualTo(1);
        assertThat(duplicates).as("every other submission should be recognised as the same instruction").isEqualTo(n - 1);

        Set<Object> uetrs = responses.stream().map(r -> r.getBody().get("uetr")).collect(Collectors.toSet());
        assertThat(uetrs).as("every response should carry the same UETR").hasSize(1);

        Integer instructionCount = jdbc.queryForObject(
                "SELECT count(*) FROM core.payment_instruction WHERE end_to_end_id = ?", Integer.class, endToEndId);
        assertThat(instructionCount).isEqualTo(1);
    }

    @RepeatedTest(5)
    void concurrentSubmissionsOfTheSameReferenceWithDifferentContentProduceOneInstructionAndOne409() throws Exception {
        String endToEndId = uniqueEndToEndId("CX");
        byte[] canonical = pain001(endToEndId, "FR1420041010050500013M02606");
        byte[] conflicting = pain001(endToEndId, "FR7630006000011234567890189");

        List<Callable<ResponseEntity<Map>>> tasks = List.of(() -> post(canonical), () -> post(conflicting));
        List<ResponseEntity<Map>> responses = fireConcurrently(tasks);

        assertThat(responses).noneMatch(r -> r.getStatusCode().is5xxServerError());
        long twoXx = responses.stream().filter(r -> r.getStatusCode().is2xxSuccessful()).count();
        long conflicts = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).count();
        assertThat(twoXx).as("exactly one submission should create the instruction").isEqualTo(1);
        assertThat(conflicts).as("the other should be rejected as a reference conflict").isEqualTo(1);

        Integer instructionCount = jdbc.queryForObject(
                "SELECT count(*) FROM core.payment_instruction WHERE end_to_end_id = ?", Integer.class, endToEndId);
        assertThat(instructionCount).isEqualTo(1);
    }

    private List<ResponseEntity<Map>> fireConcurrently(int n, Callable<ResponseEntity<Map>> task) throws Exception {
        List<Callable<ResponseEntity<Map>>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            tasks.add(task);
        }
        return fireConcurrently(tasks);
    }

    private List<ResponseEntity<Map>> fireConcurrently(List<Callable<ResponseEntity<Map>>> tasks) throws Exception {
        int n = tasks.size();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();
        for (Callable<ResponseEntity<Map>> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }

        ready.await();
        go.countDown();

        List<ResponseEntity<Map>> responses = new ArrayList<>();
        for (Future<ResponseEntity<Map>> future : futures) {
            responses.add(future.get());
        }
        pool.shutdown();
        return responses;
    }

    /**
     * EndToEndId is Max35Text and gets a "MSG-"/"PMTINF-" prefix added on top
     * in the template below, so the unique token itself has to stay short --
     * a full UUID (36 chars) blows the limit on its own before any prefix.
     */
    private static String uniqueEndToEndId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private ResponseEntity<Map> post(byte[] body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        return restTemplate.postForEntity("/v1/instructions", new HttpEntity<>(body, headers), Map.class);
    }

    private static byte[] pain001(String endToEndId, String creditorIban) {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.001.001.09">
                    <CstmrCdtTrfInitn>
                        <GrpHdr>
                            <MsgId>MSG-%1$s</MsgId>
                            <CreDtTm>2026-08-14T10:00:00</CreDtTm>
                            <NbOfTxs>1</NbOfTxs>
                            <InitgPty><Nm>Acme Gmbh</Nm></InitgPty>
                        </GrpHdr>
                        <PmtInf>
                            <PmtInfId>PMTINF-%1$s</PmtInfId>
                            <PmtMtd>TRF</PmtMtd>
                            <ReqdExctnDt><Dt>2026-08-20</Dt></ReqdExctnDt>
                            <Dbtr><Nm>Acme Gmbh</Nm></Dbtr>
                            <DbtrAcct><Id><IBAN>DE89370400440532013000</IBAN></Id></DbtrAcct>
                            <DbtrAgt><FinInstnId><BICFI>DEUTDEFFXXX</BICFI></FinInstnId></DbtrAgt>
                            <ChrgBr>SLEV</ChrgBr>
                            <CdtTrfTxInf>
                                <PmtId><EndToEndId>%1$s</EndToEndId></PmtId>
                                <Amt><InstdAmt Ccy="EUR">1000.00</InstdAmt></Amt>
                                <CdtrAgt><FinInstnId><BICFI>BNPAFRPPXXX</BICFI></FinInstnId></CdtrAgt>
                                <Cdtr><Nm>Beneficiary SARL</Nm></Cdtr>
                                <CdtrAcct><Id><IBAN>%2$s</IBAN></Id></CdtrAcct>
                            </CdtTrfTxInf>
                        </PmtInf>
                    </CstmrCdtTrfInitn>
                </Document>
                """.formatted(endToEndId, creditorIban);
        return xml.getBytes(StandardCharsets.UTF_8);
    }
}
