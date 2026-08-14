package com.kishore.payments.intake.pain001;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.intake.iso20022.generated.Document;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class Pain001MessageParserTest {

    private static Pain001MessageParser parser;

    @BeforeAll
    static void setUp() throws JAXBException {
        parser = new Pain001MessageParser(JAXBContext.newInstance(Document.class));
    }

    @Test
    void extractsAllFieldsFromAValidEurDocument() throws IOException {
        ParsedPain001Instruction parsed = parser.parse(sample("valid-single-eur.xml"));

        assertThat(parsed.debtorName()).isEqualTo("Acme Gmbh");
        assertThat(parsed.debtorAccount()).isEqualTo("DE89370400440532013000");
        assertThat(parsed.debtorAgentBic()).isEqualTo("DEUTDEFFXXX");
        assertThat(parsed.creditorName()).isEqualTo("Beneficiary SARL");
        assertThat(parsed.creditorAccount()).isEqualTo("FR1420041010050500013M02606");
        assertThat(parsed.creditorAgentBic()).isEqualTo("BNPAFRPPXXX");
        assertThat(parsed.amount()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(parsed.currency()).isEqualTo("EUR");
        assertThat(parsed.chargeBearer()).isEqualTo("SLEV");
        assertThat(parsed.requestedExecDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(parsed.endToEndId()).isEqualTo("E2E-EUR-0001");
        assertThat(parsed.uetr()).isNull();
    }

    @Test
    void extractsANonIbanAccountViaOthr() throws IOException {
        ParsedPain001Instruction parsed = parser.parse(sample("valid-single-usd.xml"));

        assertThat(parsed.debtorAccount()).isEqualTo("000123456789");
        assertThat(parsed.creditorAccount()).isEqualTo("000987654321");
        assertThat(parsed.currency()).isEqualTo("USD");
    }

    @Test
    void extractsTheUetrWhenTheMessageCarriesOne() throws IOException {
        ParsedPain001Instruction parsed = parser.parse(sample("valid-with-uetr.xml"));

        assertThat(parsed.uetr()).isEqualTo(UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6"));
    }

    private static byte[] sample(String name) throws IOException {
        try (InputStream in = Pain001MessageParserTest.class.getResourceAsStream("/samples/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Sample not found on classpath: " + name);
            }
            return in.readAllBytes();
        }
    }
}
