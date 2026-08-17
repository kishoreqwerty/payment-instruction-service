package com.kishore.payments.railsim.callback;

import com.kishore.payments.railsim.dispatch.InboundPayment;
import com.kishore.payments.railsim.iso20022.XmlSchemaValidator;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import javax.xml.namespace.QName;
import javax.xml.validation.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Builds and sends the two asynchronous messages this simulator ever
 * produces (pacs.002, pacs.004), always on a delay, always to a configured
 * callback URL, never inline in the request thread that accepted the
 * pacs.008.
 */
@Component
public class CallbackSender {

    private static final Logger log = LoggerFactory.getLogger(CallbackSender.class);

    private static final String PACS_002_NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10";
    private static final String PACS_004_NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:pacs.004.001.09";
    private static final String ROOT_ELEMENT_NAME = "Document";

    private final Pacs002Builder pacs002Builder;
    private final Pacs004Builder pacs004Builder;
    private final JAXBContext pacs002JaxbContext;
    private final JAXBContext pacs004JaxbContext;
    private final Schema pacs002Schema;
    private final Schema pacs004Schema;
    private final XmlSchemaValidator xmlSchemaValidator;
    private final ScheduledExecutorService scheduler;
    private final RestTemplate restTemplate;

    public CallbackSender(
            Pacs002Builder pacs002Builder,
            Pacs004Builder pacs004Builder,
            @Qualifier("pacs002JaxbContext") JAXBContext pacs002JaxbContext,
            @Qualifier("pacs004JaxbContext") JAXBContext pacs004JaxbContext,
            @Qualifier("pacs002Schema") Schema pacs002Schema,
            @Qualifier("pacs004Schema") Schema pacs004Schema,
            XmlSchemaValidator xmlSchemaValidator,
            ScheduledExecutorService railSimulatorScheduler,
            RestTemplate railCallbackRestTemplate) {
        this.pacs002Builder = pacs002Builder;
        this.pacs004Builder = pacs004Builder;
        this.pacs002JaxbContext = pacs002JaxbContext;
        this.pacs004JaxbContext = pacs004JaxbContext;
        this.pacs002Schema = pacs002Schema;
        this.pacs004Schema = pacs004Schema;
        this.xmlSchemaValidator = xmlSchemaValidator;
        this.scheduler = railSimulatorScheduler;
        this.restTemplate = railCallbackRestTemplate;
    }

    /**
     * Schedules a pacs.002 (ACSC/ACSP/RJCT) after delayMs. A null callbackUrl is a caller error (see
     * ScenarioConfig/application.yml), not silently skipped. {@code onStatusDetermined} fires the moment the rail
     * decides the outcome, before the message is built or sent -- the rail "knows" its own decision regardless of
     * whether the callback POST actually reaches its target. Carries {@code rejectReasonCode} alongside the status
     * (null for anything but RJCT) so a reconciliation status query can answer with the same reason code a pacs.002
     * would have carried, not just the bare status.
     */
    public ScheduledFuture<?> scheduleConfirmation(
            InboundPayment payment,
            String txStatus,
            String rejectReasonCode,
            long delayMs,
            String callbackUrl,
            BiConsumer<String, String> onStatusDetermined) {
        return scheduler.schedule(
                () -> {
                    onStatusDetermined.accept(txStatus, rejectReasonCode);
                    var document = pacs002Builder.build(payment, txStatus, rejectReasonCode);
                    byte[] xml = marshalAndValidate(
                            document,
                            com.kishore.payments.railsim.iso20022.generated.pacs002.Document.class,
                            PACS_002_NAMESPACE,
                            pacs002JaxbContext,
                            pacs002Schema,
                            "pacs.002");
                    post(callbackUrl, xml, "pacs.002", payment.uetr());
                },
                delayMs,
                TimeUnit.MILLISECONDS);
    }

    /** Schedules a pacs.004 (payment return) after delayMs. Only sent when a scenario explicitly calls for it -- never automatic. */
    public ScheduledFuture<?> scheduleReturn(InboundPayment payment, String returnReasonCode, long delayMs, String callbackUrl) {
        return scheduler.schedule(
                () -> {
                    var document = pacs004Builder.build(payment, returnReasonCode);
                    byte[] xml = marshalAndValidate(
                            document,
                            com.kishore.payments.railsim.iso20022.generated.pacs004.Document.class,
                            PACS_004_NAMESPACE,
                            pacs004JaxbContext,
                            pacs004Schema,
                            "pacs.004");
                    post(callbackUrl, xml, "pacs.004", payment.uetr());
                },
                delayMs,
                TimeUnit.MILLISECONDS);
    }

    private <T> byte[] marshalAndValidate(
            T document, Class<T> documentClass, String namespace, JAXBContext context, Schema schema, String messageType) {
        byte[] xml = marshal(document, documentClass, namespace, context);
        var result = xmlSchemaValidator.validate(xml, schema);
        if (!result.isValid()) {
            // A builder bug, not a scenario-configuration problem -- this
            // should never happen, and logging loudly rather than silently
            // sending malformed output is the point: better a visible
            // simulator defect than a future settlement-gateway debugging
            // session against input that was never valid in the first place.
            log.error("Generated {} failed its own XSD validation: {}", messageType, result.violations());
        }
        return xml;
    }

    private static <T> byte[] marshal(T document, Class<T> documentClass, String namespace, JAXBContext context) {
        try {
            Marshaller marshaller = context.createMarshaller();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // The generated Document class has no @XmlRootElement of its own
            // (the schema's root is declared via the generated
            // ObjectFactory's createDocument(...) element factory method
            // instead), so it has to be wrapped in a JAXBElement to be
            // marshallable as a document at all. Built directly from the
            // schema's own namespace + "Document" rather than importing
            // each package's ObjectFactory here, so this method stays
            // generic across message types.
            JAXBElement<T> element = new JAXBElement<>(new QName(namespace, ROOT_ELEMENT_NAME), documentClass, document);
            marshaller.marshal(element, out);
            return out.toByteArray();
        } catch (JAXBException e) {
            throw new IllegalStateException("Failed to marshal a generated ISO 20022 message", e);
        }
    }

    private void post(String callbackUrl, byte[] xml, String messageType, String uetr) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_XML);
            restTemplate.postForEntity(callbackUrl, new HttpEntity<>(xml, headers), Void.class);
        } catch (RestClientException e) {
            // The callback target being unreachable is the caller's problem
            // to notice (e.g. via reconciliation, .notes/ARCHITECTURE.md
            // §6.4) -- a real rail does not retry a status callback
            // indefinitely, and neither does this one.
            log.warn("Failed to deliver {} for UETR {} to {}", messageType, uetr, callbackUrl, e);
        }
    }
}
