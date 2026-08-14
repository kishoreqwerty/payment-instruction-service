package com.kishore.payments.intake.pain001;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

class Pain001ValidatorTest {

    private static Pain001Validator validator;

    @BeforeAll
    static void compileSchema() throws SAXException, IOException {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        Schema schema;
        try (InputStream xsd = Pain001ValidatorTest.class.getResourceAsStream("/xsd/pain.001.001.09.xsd")) {
            schema = factory.newSchema(new StreamSource(xsd));
        }
        validator = new Pain001Validator(schema);
    }

    @Test
    void validDocumentPassesWithNoViolations() throws IOException {
        Pain001Validator.ValidationResult result = validator.validate(sample("valid-single-eur.xml"));

        assertThat(result.wellFormed()).isTrue();
        assertThat(result.violations()).isEmpty();
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void malformedXmlIsNotWellFormedAndReportsNoViolations() throws IOException {
        Pain001Validator.ValidationResult result = validator.validate(sample("malformed.xml"));

        assertThat(result.wellFormed()).isFalse();
        assertThat(result.isValid()).isFalse();
    }

    @Test
    void missingAmountIsWellFormedButSchemaInvalid() throws IOException {
        Pain001Validator.ValidationResult result = validator.validate(sample("schema-invalid-missing-amount.xml"));

        assertThat(result.wellFormed()).isTrue();
        assertThat(result.violations()).isNotEmpty();
        assertThat(result.isValid()).isFalse();
    }

    @Test
    void badDateIsWellFormedButSchemaInvalid() throws IOException {
        Pain001Validator.ValidationResult result = validator.validate(sample("schema-invalid-bad-date.xml"));

        assertThat(result.wellFormed()).isTrue();
        assertThat(result.violations()).isNotEmpty();
    }

    @Test
    void collectsMultipleViolationsInOnePass() {
        // Missing Amt AND a malformed date in the same document: a validator
        // that stops at the first error would report exactly one violation.
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.001.001.09">
                    <CstmrCdtTrfInitn>
                        <GrpHdr>
                            <MsgId>MSG-MULTI-0001</MsgId>
                            <CreDtTm>2026-08-14T10:00:00</CreDtTm>
                            <NbOfTxs>1</NbOfTxs>
                            <InitgPty><Nm>Acme Gmbh</Nm></InitgPty>
                        </GrpHdr>
                        <PmtInf>
                            <PmtInfId>PMTINF-MULTI-0001</PmtInfId>
                            <PmtMtd>TRF</PmtMtd>
                            <ReqdExctnDt><Dt>not-a-date</Dt></ReqdExctnDt>
                            <Dbtr><Nm>Acme Gmbh</Nm></Dbtr>
                            <DbtrAcct><Id><IBAN>DE89370400440532013000</IBAN></Id></DbtrAcct>
                            <DbtrAgt><FinInstnId><BICFI>DEUTDEFFXXX</BICFI></FinInstnId></DbtrAgt>
                            <CdtTrfTxInf>
                                <PmtId><EndToEndId>E2E-MULTI-0001</EndToEndId></PmtId>
                                <CdtrAgt><FinInstnId><BICFI>BNPAFRPPXXX</BICFI></FinInstnId></CdtrAgt>
                                <Cdtr><Nm>Beneficiary SARL</Nm></Cdtr>
                                <CdtrAcct><Id><IBAN>FR1420041010050500013M02606</IBAN></Id></CdtrAcct>
                            </CdtTrfTxInf>
                        </PmtInf>
                    </CstmrCdtTrfInitn>
                </Document>
                """;

        Pain001Validator.ValidationResult result = validator.validate(xml.getBytes(StandardCharsets.UTF_8));

        assertThat(result.wellFormed()).isTrue();
        assertThat(result.violations()).as("both the bad date and the missing Amt should be reported").hasSizeGreaterThanOrEqualTo(2);
    }

    private static byte[] sample(String name) throws IOException {
        try (InputStream in = Pain001ValidatorTest.class.getResourceAsStream("/samples/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Sample not found on classpath: " + name);
            }
            return in.readAllBytes();
        }
    }
}
