import { explainReasonCode } from "./reasonCodes";

/**
 * Brief §3: "The ISO reason code must appear... but so must a human
 * explanation... `AC01` alongside 'the creditor account number failed its
 * checksum' is the right density." The case's own reasonDetail is already
 * that sentence for REPAIRABLE business failures (set by whichever
 * pipeline stage raised the exception); the static glossary in
 * reasonCodes.ts fills in when a case has a code but no free-text detail
 * (e.g. TRANSIENT/INVESTIGATION cases -- see openInvestigationCase in the
 * backend tests, which passes a null reasonCode entirely).
 */
export function ReasonExplanation({ reasonCode, reasonDetail }: { reasonCode: string | null; reasonDetail: string | null }) {
  const glossed = explainReasonCode(reasonCode);
  const explanation = reasonDetail ?? glossed;
  return (
    <div className="reason-block">
      {reasonCode && <span className="reason-code">{reasonCode}</span>}
      <span className="reason-detail">{explanation ?? "No further detail was recorded for this failure."}</span>
    </div>
  );
}
