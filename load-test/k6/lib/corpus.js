import { SharedArray } from 'k6/data';

// Loaded once and shared read-only across every VU (k6's SharedArray contract) --
// avoids each VU parsing its own copy of a multi-MB corpus file.
export function loadCorpus(path) {
  return new SharedArray('corpus', function () {
    const text = open(path);
    return text
      .split('\n')
      .filter((line) => line.length > 0)
      .map((line) => JSON.parse(line));
  });
}

// The load corpus's XML carries the literal token __E2E__ in MsgId/PmtInfId/EndToEndId
// (see LoadCorpusGenerator's javadoc for why: 20,000 template rows cycled across a
// multi-hour run need a per-request-unique id substituted at request time rather than
// pre-generating and storing one row per eventual HTTP request).
//
// ISO 20022's Max35Text caps EndToEndId at 35 chars, and MsgId/PmtInfId add their own
// "MSG-"/"PMTINF-" prefix on top of it (see LoadCorpusGenerator.toXml), so the token
// itself has to stay well under 35 chars, not just under it. An earlier version built
// the token as `${row.endToEndId}-${vu}-${iter}-${Date.now()}-${random 0..1e9}` --
// row.endToEndId is the literal placeholder "__E2E__" for every load-corpus row, so
// that alone burned 7 of the 35 chars for nothing, and the full token came out at
// 35-40 chars before the "MSG-"/"PMTINF-" prefix was even added. Every one of
// 1,004,999 requests in one run failed XSD validation (cvc-maxLength-valid) on MsgId
// and PmtInfId as a result -- 100% http_req_failed, discovered only because the DB
// row count stayed flat across a full 37-minute run rather than growing. Base36
// timestamp+vu+iter+a short random suffix keeps the token to ~17 chars: vu/iter alone
// already guarantee uniqueness within one k6 run (no two iterations share a (vu,iter)
// pair), so this is about staying short, not about generating more entropy.
export function materialize(row, vu, iter) {
  const uniqueId = 'LT' + Date.now().toString(36) + vu.toString(36) + iter.toString(36)
    + Math.floor(Math.random() * 1296).toString(36);
  return row.xml.split('__E2E__').join(uniqueId);
}
