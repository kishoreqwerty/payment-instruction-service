package com.kishore.payments.railsim.dispatch;

import com.kishore.payments.railsim.iso20022.SecureXml;
import com.kishore.payments.railsim.iso20022.generated.pacs008.AccountIdentification4Choice;
import com.kishore.payments.railsim.iso20022.generated.pacs008.CashAccount38;
import com.kishore.payments.railsim.iso20022.generated.pacs008.CreditTransferTransaction39;
import com.kishore.payments.railsim.iso20022.generated.pacs008.Document;
import com.kishore.payments.railsim.iso20022.generated.pacs008.FIToFICustomerCreditTransferV08;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import java.io.ByteArrayInputStream;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Extracts the fields this simulator needs from a pacs.008 message that has
 * already passed {@link com.kishore.payments.railsim.iso20022.XmlSchemaValidator}
 * against the pacs.008 schema. Single-transaction envelopes only, matching
 * what settlement-gateway is expected to send (one instruction dispatched
 * per message) -- the first CdtTrfTxInf is the payment.
 */
@Component
public class Pacs008Parser {

    private final JAXBContext jaxbContext;

    public Pacs008Parser(@Qualifier("pacs008JaxbContext") JAXBContext pacs008JaxbContext) {
        this.jaxbContext = pacs008JaxbContext;
    }

    public InboundPayment parse(byte[] payload) {
        Document document = unmarshal(payload);
        FIToFICustomerCreditTransferV08 fiToFi = document.getFIToFICstmrCdtTrf();
        String messageId = fiToFi.getGrpHdr().getMsgId();
        CreditTransferTransaction39 txInf = fiToFi.getCdtTrfTxInf().get(0);

        return new InboundPayment(
                txInf.getPmtId().getUETR(),
                messageId,
                txInf.getPmtId().getEndToEndId(),
                txInf.getPmtId().getInstrId(),
                txInf.getPmtId().getTxId(),
                txInf.getIntrBkSttlmAmt().getValue(),
                txInf.getIntrBkSttlmAmt().getCcy(),
                accountId(txInf.getCdtrAcct()),
                txInf.getCdtrAgt().getFinInstnId().getBICFI(),
                txInf.getDbtrAgt().getFinInstnId().getBICFI());
    }

    private Document unmarshal(byte[] payload) {
        try {
            Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
            XMLStreamReader reader = SecureXml.newSecureXmlInputFactory().createXMLStreamReader(new ByteArrayInputStream(payload));
            JAXBElement<Document> element = unmarshaller.unmarshal(reader, Document.class);
            return element.getValue();
        } catch (JAXBException | XMLStreamException e) {
            throw new IllegalStateException("Failed to unmarshal a pacs.008 message that already passed XSD validation", e);
        }
    }

    private static String accountId(CashAccount38 account) {
        AccountIdentification4Choice id = account.getId();
        if (id.getIBAN() != null) {
            return id.getIBAN();
        }
        if (id.getOthr() != null) {
            return id.getOthr().getId();
        }
        throw new IllegalStateException("CashAccount38.Id has neither IBAN nor Othr set, which the XSD's choice should have prevented");
    }
}
