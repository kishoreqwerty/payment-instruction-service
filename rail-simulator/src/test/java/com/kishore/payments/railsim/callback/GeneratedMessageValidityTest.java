package com.kishore.payments.railsim.callback;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.railsim.dispatch.InboundPayment;
import com.kishore.payments.railsim.iso20022.XmlSchemaValidator;
import com.kishore.payments.railsim.support.AbstractRailSimulatorTest;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import javax.xml.namespace.QName;
import javax.xml.validation.Schema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Confirms that what CallbackSender actually sends is valid per the schemas
 * it was built against -- the same defensive check CallbackSender itself
 * performs before sending, but asserted here as a first-class test rather
 * than left to a log line no one is watching.
 */
class GeneratedMessageValidityTest extends AbstractRailSimulatorTest {

    private static final String PACS_002_NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10";
    private static final String PACS_004_NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:pacs.004.001.09";

    @Autowired
    private Pacs002Builder pacs002Builder;

    @Autowired
    private Pacs004Builder pacs004Builder;

    @Autowired
    private XmlSchemaValidator xmlSchemaValidator;

    @Autowired
    @Qualifier("pacs002Schema")
    private Schema pacs002Schema;

    @Autowired
    @Qualifier("pacs004Schema")
    private Schema pacs004Schema;

    @Autowired
    @Qualifier("pacs002JaxbContext")
    private JAXBContext pacs002JaxbContext;

    @Autowired
    @Qualifier("pacs004JaxbContext")
    private JAXBContext pacs004JaxbContext;

    @Test
    void generatedPacs002ConfirmationValidatesAgainstItsOwnSchema() {
        InboundPayment payment = samplePayment();
        var document = pacs002Builder.build(payment, "ACSC", null);

        byte[] xml = marshal(document,
                com.kishore.payments.railsim.iso20022.generated.pacs002.Document.class, PACS_002_NAMESPACE, pacs002JaxbContext);
        var result = xmlSchemaValidator.validate(xml, pacs002Schema);

        assertThat(result.isValid()).as("violations: %s", result.violations()).isTrue();
    }

    @Test
    void generatedPacs002RejectionValidatesAgainstItsOwnSchema() {
        InboundPayment payment = samplePayment();
        var document = pacs002Builder.build(payment, "RJCT", "AC04");

        byte[] xml = marshal(document,
                com.kishore.payments.railsim.iso20022.generated.pacs002.Document.class, PACS_002_NAMESPACE, pacs002JaxbContext);
        var result = xmlSchemaValidator.validate(xml, pacs002Schema);

        assertThat(result.isValid()).as("violations: %s", result.violations()).isTrue();
    }

    @Test
    void generatedPacs004ReturnValidatesAgainstItsOwnSchema() {
        InboundPayment payment = samplePayment();
        var document = pacs004Builder.build(payment, "AM04");

        byte[] xml = marshal(document,
                com.kishore.payments.railsim.iso20022.generated.pacs004.Document.class, PACS_004_NAMESPACE, pacs004JaxbContext);
        var result = xmlSchemaValidator.validate(xml, pacs004Schema);

        assertThat(result.isValid()).as("violations: %s", result.violations()).isTrue();
    }

    private static InboundPayment samplePayment() {
        return new InboundPayment(
                "8a562c67-ca16-48ba-b074-65581be6f099",
                "MSGID-VALIDITY",
                "E2E-VALIDITY",
                "INSTR-VALIDITY",
                "TX-VALIDITY",
                new BigDecimal("500.00"),
                "USD",
                "CDTRACCT-VALIDITY",
                "DEUTDEFFXXX",
                "CHASUS33XXX");
    }

    private static <T> byte[] marshal(T document, Class<T> documentClass, String namespace, JAXBContext context) {
        try {
            Marshaller marshaller = context.createMarshaller();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JAXBElement<T> element = new JAXBElement<>(new QName(namespace, "Document"), documentClass, document);
            marshaller.marshal(element, out);
            return out.toByteArray();
        } catch (JAXBException e) {
            throw new IllegalStateException(e);
        }
    }
}
