// Phase 12 §1: "Measure the generator alone: how fast can it feed instructions to a
// null endpoint that accepts and discards?" This is not a system measurement -- it is
// an instrument check. It must run, and its number must comfortably clear 500/sec,
// before any result against the real pipeline is trusted.
import http from 'k6/http';
import { check } from 'k6';
import { loadCorpus, materialize } from './lib/corpus.js';

const corpus = loadCorpus('../corpus/load-corpus.ndjson');
const NULL_ENDPOINT = __ENV.NULL_ENDPOINT_URL || 'http://localhost:9999/null';

export const options = {
  scenarios: {
    ceiling: {
      executor: 'ramping-vus',
      startVUs: 10,
      stages: [
        { duration: '30s', target: 100 },
        { duration: '30s', target: 300 },
        { duration: '30s', target: 600 },
        { duration: '30s', target: 600 }, // hold at the top step to see if it's still climbing or has plateaued
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const row = corpus[Math.floor(Math.random() * corpus.length)];
  const xml = materialize(row, __VU, __ITER);
  const res = http.post(NULL_ENDPOINT, xml, { headers: { 'Content-Type': 'application/xml' } });
  check(res, { 'status is 200': (r) => r.status === 200 });
}
