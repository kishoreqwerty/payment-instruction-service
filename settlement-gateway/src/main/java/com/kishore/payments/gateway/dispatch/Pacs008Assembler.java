package com.kishore.payments.gateway.dispatch;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.gateway.failure.BusinessFailureException;
import com.kishore.payments.gateway.failure.FailureDetail;
import com.kishore.payments.gateway.iso20022.XmlDateTimes;
import com.kishore.payments.gateway.iso20022.XmlSchemaValidator;
import com.kishore.payments.gateway.iso20022.generated.pacs008.AccountIdentification4Choice;
import com.kishore.payments.gateway.iso20022.generated.pacs008.ActiveCurrencyAndAmount;
import com.kishore.payments.gateway.iso20022.generated.pacs008.BranchAndFinancialInstitutionIdentification6;
import com.kishore.payments.gateway.iso20022.generated.pacs008.CashAccount38;
import com.kishore.payments.gateway.iso20022.generated.pacs008.ChargeBearerType1Code;
import com.kishore.payments.gateway.iso20022.generated.pacs008.CreditTransferTransaction39;
import com.kishore.payments.gateway.iso20022.generated.pacs008.Document;
import com.kishore.payments.gateway.iso20022.generated.pacs008.FIToFICustomerCreditTransferV08;
import com.kishore.payments.gateway.iso20022.generated.pacs008.FinancialInstitutionIdentification18;
import com.kishore.payments.gateway.iso20022.generated.pacs008.GenericAccountIdentification1;
import com.kishore.payments.gateway.iso20022.generated.pacs008.GroupHeader93;
import com.kishore.payments.gateway.iso20022.generated.pacs008.PartyIdentification135;
import com.kishore.payments.gateway.iso20022.generated.pacs008.PaymentIdentification7;
import com.kishore.payments.gateway.iso20022.generated.pacs008.SettlementInstruction7;
import com.kishore.payments.gateway.iso20022.generated.pacs008.SettlementMethod1Code;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.regex.Pattern;
import javax.xml.namespace.QName;
import javax.xml.validation.Schema;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Builds and marshals pacs.008.001.08 from a ROUTED instruction, then
 * validates the result against the XSD before returning it -- a
 * schema-invalid message reaching the rail looks like a rail-side business
 * rejection, which is miserable to diagnose from the exception queue, so
 * this class never lets one out. A validation failure here means the
 * instruction or the reference data used to enrich it is wrong; no retry of
 * the send fixes that, so it is a {@link BusinessFailureException}.
 */
@Component
public class Pacs008Assembler {

    private static final String NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08";
    private static final Pattern IBAN_SHAPE = Pattern.compile("^[A-Z]{2}\\d{2}[A-Z0-9]+$");

    private final JAXBContext jaxbContext;
    private final Schema schema;
    private final XmlSchemaValidator xmlSchemaValidator;

    public Pacs008Assembler(
            @Qualifier("pacs008JaxbContext") JAXBContext jaxbContext,
            @Qualifier("pacs008Schema") Schema schema,
            XmlSchemaValidator xmlSchemaValidator) {
        this.jaxbContext = jaxbContext;
        this.schema = schema;
        this.xmlSchemaValidator = xmlSchemaValidator;
    }

    public byte[] assemble(PaymentInstructionEntity instruction) {
        Document document = buildDocument(instruction);
        byte[] xml = marshal(document);

        XmlSchemaValidator.ValidationResult result = xmlSchemaValidator.validate(xml, schema);
        if (!result.isValid()) {
            throw new BusinessFailureException(
                    FailureStage.DISPATCH,
                    new FailureDetail(
                            null,
                            Repairability.REPAIRABLE,
                            null,
                            "Assembled pacs.008 for instruction " + instruction.getInstructionId()
                                    + " failed its own XSD validation: " + result.violations()));
        }
        return xml;
    }

    private static Document buildDocument(PaymentInstructionEntity instruction) {
        PaymentIdentification7 paymentId = new PaymentIdentification7();
        paymentId.setEndToEndId(instruction.getEndToEndId());
        paymentId.setUETR(instruction.getUetr().toString());

        ActiveCurrencyAndAmount amount = new ActiveCurrencyAndAmount();
        amount.setValue(instruction.getAmount());
        amount.setCcy(instruction.getCurrency());

        CreditTransferTransaction39 txInf = new CreditTransferTransaction39();
        txInf.setPmtId(paymentId);
        txInf.setIntrBkSttlmAmt(amount);
        if (instruction.getSettlementDate() != null) {
            txInf.setIntrBkSttlmDt(XmlDateTimes.date(instruction.getSettlementDate()));
        }
        txInf.setChrgBr(chargeBearer(instruction.getChargeBearer()));
        txInf.setDbtr(party(instruction.getDebtorName()));
        txInf.setDbtrAcct(account(instruction.getDebtorAccount()));
        txInf.setDbtrAgt(agent(instruction.getDebtorAgentBic()));
        txInf.setCdtrAgt(agent(instruction.getCreditorAgentBic()));
        txInf.setCdtr(party(instruction.getCreditorName()));
        txInf.setCdtrAcct(account(instruction.getCreditorAccount()));

        SettlementInstruction7 settlementInstruction = new SettlementInstruction7();
        settlementInstruction.setSttlmMtd(SettlementMethod1Code.CLRG);

        GroupHeader93 groupHeader = new GroupHeader93();
        groupHeader.setMsgId(instruction.getInstructionId().toString().replace("-", ""));
        groupHeader.setCreDtTm(XmlDateTimes.now());
        groupHeader.setNbOfTxs("1");
        groupHeader.setSttlmInf(settlementInstruction);

        FIToFICustomerCreditTransferV08 body = new FIToFICustomerCreditTransferV08();
        body.setGrpHdr(groupHeader);
        body.getCdtTrfTxInf().add(txInf);

        Document document = new Document();
        document.setFIToFICstmrCdtTrf(body);
        return document;
    }

    private static ChargeBearerType1Code chargeBearer(String chargeBearer) {
        if (chargeBearer == null) {
            return ChargeBearerType1Code.SHAR;
        }
        return ChargeBearerType1Code.fromValue(chargeBearer);
    }

    private static PartyIdentification135 party(String name) {
        PartyIdentification135 party = new PartyIdentification135();
        party.setNm(name);
        return party;
    }

    private static BranchAndFinancialInstitutionIdentification6 agent(String bic) {
        FinancialInstitutionIdentification18 finInstnId = new FinancialInstitutionIdentification18();
        finInstnId.setBICFI(bic);
        BranchAndFinancialInstitutionIdentification6 agent = new BranchAndFinancialInstitutionIdentification6();
        agent.setFinInstnId(finInstnId);
        return agent;
    }

    private static CashAccount38 account(String accountId) {
        AccountIdentification4Choice id = new AccountIdentification4Choice();
        if (IBAN_SHAPE.matcher(accountId).matches()) {
            id.setIBAN(accountId);
        } else {
            GenericAccountIdentification1 other = new GenericAccountIdentification1();
            other.setId(accountId);
            id.setOthr(other);
        }
        CashAccount38 account = new CashAccount38();
        account.setId(id);
        return account;
    }

    private byte[] marshal(Document document) {
        try {
            Marshaller marshaller = jaxbContext.createMarshaller();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JAXBElement<Document> element = new JAXBElement<>(new QName(NAMESPACE, "Document"), Document.class, document);
            marshaller.marshal(element, out);
            return out.toByteArray();
        } catch (JAXBException e) {
            throw new IllegalStateException("Failed to marshal an assembled pacs.008", e);
        }
    }
}
