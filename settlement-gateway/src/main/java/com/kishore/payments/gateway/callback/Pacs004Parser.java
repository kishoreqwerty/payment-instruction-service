package com.kishore.payments.gateway.callback;

import com.kishore.payments.gateway.iso20022.SecureXml;
import com.kishore.payments.gateway.iso20022.generated.pacs004.Document;
import com.kishore.payments.gateway.iso20022.generated.pacs004.PaymentTransaction112;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import java.io.ByteArrayInputStream;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Extracts what this gateway needs from a pacs.004 that has already passed {@link com.kishore.payments.gateway.iso20022.XmlSchemaValidator}. */
@Component
public class Pacs004Parser {

    private final JAXBContext jaxbContext;

    public Pacs004Parser(@Qualifier("pacs004JaxbContext") JAXBContext pacs004JaxbContext) {
        this.jaxbContext = pacs004JaxbContext;
    }

    public InboundReturn parse(byte[] payload) {
        Document document = unmarshal(payload);
        PaymentTransaction112 tx = document.getPmtRtr().getTxInf().get(0);
        String reasonCode = tx.getRtrRsnInf().isEmpty() ? null : tx.getRtrRsnInf().get(0).getRsn().getCd();
        return new InboundReturn(tx.getOrgnlUETR(), reasonCode);
    }

    private Document unmarshal(byte[] payload) {
        try {
            Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
            XMLStreamReader reader = SecureXml.newSecureXmlInputFactory().createXMLStreamReader(new ByteArrayInputStream(payload));
            JAXBElement<Document> element = unmarshaller.unmarshal(reader, Document.class);
            return element.getValue();
        } catch (JAXBException | XMLStreamException e) {
            throw new IllegalStateException("Failed to unmarshal a pacs.004 message that already passed XSD validation", e);
        }
    }
}
