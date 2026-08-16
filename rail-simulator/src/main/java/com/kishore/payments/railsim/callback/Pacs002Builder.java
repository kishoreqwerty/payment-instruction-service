package com.kishore.payments.railsim.callback;

import com.kishore.payments.railsim.dispatch.InboundPayment;
import com.kishore.payments.railsim.iso20022.generated.pacs002.Document;
import com.kishore.payments.railsim.iso20022.generated.pacs002.FIToFIPaymentStatusReportV10;
import com.kishore.payments.railsim.iso20022.generated.pacs002.GroupHeader91;
import com.kishore.payments.railsim.iso20022.generated.pacs002.OriginalGroupInformation29;
import com.kishore.payments.railsim.iso20022.generated.pacs002.PaymentTransaction110;
import com.kishore.payments.railsim.iso20022.generated.pacs002.StatusReason6Choice;
import com.kishore.payments.railsim.iso20022.generated.pacs002.StatusReasonInformation12;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds a pacs.002 (Payment Status Report) confirming or rejecting one
 * previously-accepted payment. Only the fields the schema actually requires
 * (GrpHdr.MsgId/CreDtTm) plus what correlation and the failure taxonomy
 * need (OrgnlUETR, TxSts, StsRsnInf for a rejection) are populated -- see
 * .notes/reports/PHASE-5-REPORT.md for why this simulator stops there
 * rather than modelling the full message.
 */
@Component
public class Pacs002Builder {

    public Document build(InboundPayment original, String txStatus, String rejectReasonCode) {
        PaymentTransaction110 txInfAndSts = new PaymentTransaction110();
        txInfAndSts.setOrgnlGrpInf(originalGroupInfo(original));
        txInfAndSts.setOrgnlEndToEndId(original.endToEndId());
        txInfAndSts.setOrgnlTxId(original.transactionId());
        txInfAndSts.setOrgnlUETR(original.uetr());
        txInfAndSts.setTxSts(txStatus);
        if (rejectReasonCode != null) {
            StatusReason6Choice reason = new StatusReason6Choice();
            reason.setCd(rejectReasonCode);
            StatusReasonInformation12 reasonInfo = new StatusReasonInformation12();
            reasonInfo.setRsn(reason);
            txInfAndSts.getStsRsnInf().add(reasonInfo);
        }

        GroupHeader91 groupHeader = new GroupHeader91();
        groupHeader.setMsgId(newMsgId());
        groupHeader.setCreDtTm(XmlDateTimes.now());

        FIToFIPaymentStatusReportV10 report = new FIToFIPaymentStatusReportV10();
        report.setGrpHdr(groupHeader);
        report.getTxInfAndSts().add(txInfAndSts);

        Document document = new Document();
        document.setFIToFIPmtStsRpt(report);
        return document;
    }

    private static String newMsgId() {
        // Max35Text: a UUID's canonical 36-character hyphenated form doesn't
        // fit, so the hyphens are dropped rather than truncating (which
        // would risk collisions).
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static OriginalGroupInformation29 originalGroupInfo(InboundPayment original) {
        OriginalGroupInformation29 originalGroupInfo = new OriginalGroupInformation29();
        originalGroupInfo.setOrgnlMsgId(original.messageId());
        originalGroupInfo.setOrgnlMsgNmId("pacs.008.001.08");
        return originalGroupInfo;
    }
}
