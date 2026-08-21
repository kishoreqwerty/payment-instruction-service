/**
 * Plain-language glosses for the ISO 20022 external status/reason codes
 * (pain.002/pacs.002 "StatusReasonCode") this system actually raises.
 * Static and client-side deliberately: this is a fixed, published external
 * standard, not application state exception-service owns or that changes
 * per phase, so serving it from the API would just be a network round trip
 * for a lookup table this small. Falls back to the case's own reasonDetail
 * (already a human sentence -- see ExceptionCaseEntity/InstructionExceptionEvent)
 * when a code isn't in this list, so an unmapped code degrades to "still
 * readable," not "blank."
 */
const REASON_CODES: Record<string, string> = {
  AC01: "The account number is invalid or does not exist.",
  AC03: "The creditor's account number is invalid.",
  AC04: "The account has been closed.",
  AC06: "The account is blocked.",
  AG01: "The transaction type is not supported or authorised on this account.",
  AM04: "There are insufficient funds to complete the payment.",
  AM05: "This payment is a duplicate of one already processed.",
  BE04: "The creditor's address is missing or invalid.",
  BE05: "The creditor's identification is missing or invalid.",
  DT01: "The requested execution date is invalid, e.g. in the past.",
  MD07: "The creditor is deceased.",
  RC01: "The bank identifier code (BIC) is invalid or missing.",
  RR04: "Regulatory reporting information is missing or invalid.",
};

export function explainReasonCode(reasonCode: string | null): string | null {
  if (!reasonCode) return null;
  return REASON_CODES[reasonCode] ?? null;
}
