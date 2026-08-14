package com.kishore.payments.intake.pain001;

import com.kishore.payments.intake.iso20022.generated.AccountIdentification4Choice;
import com.kishore.payments.intake.iso20022.generated.CashAccount38;
import com.kishore.payments.intake.iso20022.generated.CreditTransferTransaction34;
import com.kishore.payments.intake.iso20022.generated.CustomerCreditTransferInitiationV09;
import com.kishore.payments.intake.iso20022.generated.DateAndDateTime2Choice;
import com.kishore.payments.intake.iso20022.generated.Document;
import com.kishore.payments.intake.iso20022.generated.PaymentInstruction30;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.stereotype.Component;

/**
 * Extracts the fields intake needs from a pain.001 message that has already
 * passed {@link Pain001Validator}. Single-transaction envelopes only (see
 * .notes/ARCHITECTURE.md §1.2) -- the first (and, for a valid envelope, only)
 * PmtInf/CdtTrfTxInf is the instruction.
 */
@Component
public class Pain001MessageParser {

    private final JAXBContext jaxbContext;

    public Pain001MessageParser(JAXBContext jaxbContext) {
        this.jaxbContext = jaxbContext;
    }

    public ParsedPain001Instruction parse(byte[] payload) {
        Document document = unmarshal(payload);
        CustomerCreditTransferInitiationV09 initn = document.getCstmrCdtTrfInitn();
        PaymentInstruction30 pmtInf = initn.getPmtInf().get(0);
        CreditTransferTransaction34 txInf = pmtInf.getCdtTrfTxInf().get(0);

        String uetrText = txInf.getPmtId().getUETR();

        return new ParsedPain001Instruction(
                pmtInf.getDbtr().getNm(),
                accountId(pmtInf.getDbtrAcct()),
                pmtInf.getDbtrAgt().getFinInstnId().getBICFI(),
                txInf.getCdtr().getNm(),
                accountId(txInf.getCdtrAcct()),
                txInf.getCdtrAgt().getFinInstnId().getBICFI(),
                txInf.getAmt().getInstdAmt().getValue(),
                txInf.getAmt().getInstdAmt().getCcy(),
                pmtInf.getChrgBr() != null ? pmtInf.getChrgBr().value() : null,
                toLocalDate(pmtInf.getReqdExctnDt()),
                txInf.getPmtId().getEndToEndId(),
                txInf.getPmtId().getInstrId(),
                uetrText != null ? UUID.fromString(uetrText) : null);
    }

    private Document unmarshal(byte[] payload) {
        try {
            Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
            XMLStreamReader reader =
                    SecureXml.newSecureXmlInputFactory().createXMLStreamReader(new ByteArrayInputStream(payload));
            JAXBElement<Document> element = unmarshaller.unmarshal(reader, Document.class);
            return element.getValue();
        } catch (JAXBException | XMLStreamException e) {
            throw new IllegalStateException("Failed to unmarshal a pain.001 message that already passed XSD validation", e);
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

    private static LocalDate toLocalDate(DateAndDateTime2Choice choice) {
        XMLGregorianCalendar calendar = choice.getDt() != null ? choice.getDt() : choice.getDtTm();
        return LocalDate.of(calendar.getYear(), calendar.getMonth(), calendar.getDay());
    }
}
