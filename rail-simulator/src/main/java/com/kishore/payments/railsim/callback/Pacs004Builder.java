package com.kishore.payments.railsim.callback;

import com.kishore.payments.railsim.dispatch.InboundPayment;
import com.kishore.payments.railsim.iso20022.generated.pacs004.Document;
import com.kishore.payments.railsim.iso20022.generated.pacs004.GroupHeader90;
import com.kishore.payments.railsim.iso20022.generated.pacs004.OriginalGroupInformation29;
import com.kishore.payments.railsim.iso20022.generated.pacs004.PaymentReturnReason6;
import com.kishore.payments.railsim.iso20022.generated.pacs004.PaymentReturnV09;
import com.kishore.payments.railsim.iso20022.generated.pacs004.PaymentTransaction112;
import com.kishore.payments.railsim.iso20022.generated.pacs004.ReturnReason5Choice;
import com.kishore.payments.railsim.iso20022.generated.pacs004.SettlementInstruction7;
import com.kishore.payments.railsim.iso20022.generated.pacs004.SettlementMethod1Code;
import com.kishore.payments.railsim.iso20022.generated.pacs004.ActiveCurrencyAndAmount;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds a pacs.004 (Payment Return): funds unwound after settlement, sent
 * only when a scenario calls for it, never automatically -- see
 * .notes/ARCHITECTURE.md §6.1's "Creditor bank returns pacs.004" row. The
 * settlement method is fixed at CLRG (clearing); this simulator has no
 * concept of correspondent/nostro accounts to make INDA/INGA/COVE
 * meaningful.
 */
@Component
public class Pacs004Builder {

    public Document build(InboundPayment original, String returnReasonCode) {
        PaymentTransaction112 txInf = new PaymentTransaction112();
        txInf.setOrgnlGrpInf(originalGroupInfo(original));
        txInf.setOrgnlEndToEndId(original.endToEndId());
        txInf.setOrgnlTxId(original.transactionId());
        txInf.setOrgnlUETR(original.uetr());
        txInf.setRtrdIntrBkSttlmAmt(amount(original));
        if (returnReasonCode != null) {
            ReturnReason5Choice reason = new ReturnReason5Choice();
            reason.setCd(returnReasonCode);
            PaymentReturnReason6 reasonInfo = new PaymentReturnReason6();
            reasonInfo.setRsn(reason);
            txInf.getRtrRsnInf().add(reasonInfo);
        }

        SettlementInstruction7 settlementInstruction = new SettlementInstruction7();
        settlementInstruction.setSttlmMtd(SettlementMethod1Code.CLRG);

        GroupHeader90 groupHeader = new GroupHeader90();
        groupHeader.setMsgId(newMsgId());
        groupHeader.setCreDtTm(XmlDateTimes.now());
        groupHeader.setNbOfTxs("1");
        groupHeader.setSttlmInf(settlementInstruction);

        PaymentReturnV09 paymentReturn = new PaymentReturnV09();
        paymentReturn.setGrpHdr(groupHeader);
        paymentReturn.getTxInf().add(txInf);

        Document document = new Document();
        document.setPmtRtr(paymentReturn);
        return document;
    }

    private static String newMsgId() {
        // Max35Text: a UUID's canonical 36-character hyphenated form doesn't
        // fit, so the hyphens are dropped rather than truncating (which
        // would risk collisions).
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static ActiveCurrencyAndAmount amount(InboundPayment original) {
        ActiveCurrencyAndAmount amount = new ActiveCurrencyAndAmount();
        amount.setValue(original.amount());
        amount.setCcy(original.currency());
        return amount;
    }

    private static OriginalGroupInformation29 originalGroupInfo(InboundPayment original) {
        OriginalGroupInformation29 originalGroupInfo = new OriginalGroupInformation29();
        originalGroupInfo.setOrgnlMsgId(original.messageId());
        originalGroupInfo.setOrgnlMsgNmId("pacs.008.001.08");
        return originalGroupInfo;
    }
}
