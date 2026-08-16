package com.kishore.payments.gateway.callback;

import com.kishore.payments.gateway.iso20022.SecureXml;
import com.kishore.payments.gateway.iso20022.generated.pacs002.Document;
import com.kishore.payments.gateway.iso20022.generated.pacs002.PaymentTransaction110;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import java.io.ByteArrayInputStream;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Extracts what this gateway needs from a pacs.002 that has already passed {@link com.kishore.payments.gateway.iso20022.XmlSchemaValidator}. */
@Component
public class Pacs002Parser {

    private final JAXBContext jaxbContext;

    public Pacs002Parser(@Qualifier("pacs002JaxbContext") JAXBContext pacs002JaxbContext) {
        this.jaxbContext = pacs002JaxbContext;
    }

    public InboundConfirmation parse(byte[] payload) {
        Document document = unmarshal(payload);
        PaymentTransaction110 tx = document.getFIToFIPmtStsRpt().getTxInfAndSts().get(0);
        String reasonCode = tx.getStsRsnInf().isEmpty() ? null : tx.getStsRsnInf().get(0).getRsn().getCd();
        return new InboundConfirmation(tx.getOrgnlUETR(), tx.getTxSts(), reasonCode);
    }

    private Document unmarshal(byte[] payload) {
        try {
            Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
            XMLStreamReader reader = SecureXml.newSecureXmlInputFactory().createXMLStreamReader(new ByteArrayInputStream(payload));
            JAXBElement<Document> element = unmarshaller.unmarshal(reader, Document.class);
            return element.getValue();
        } catch (JAXBException | XMLStreamException e) {
            throw new IllegalStateException("Failed to unmarshal a pacs.002 message that already passed XSD validation", e);
        }
    }
}
