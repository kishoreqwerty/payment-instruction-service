// Phase 12 (.notes/reports/PHASE-12-REPORT.md section 4.4): a second data point requested
// specifically to separate "this host cannot sustain 500/sec" from "there is a remaining code
// defect" -- same profile shape as sustained-500.js, scaled to half the target rate and half the
// sustain duration (250/sec, 15 minutes) rather than a different kind of test. If the backlog
// stays bounded here, the host-capacity explanation is confirmed and this is also a real
// sustained-safe throughput figure, not just a negative result.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { loadCorpus, materialize } from './lib/corpus.js';

const corpus = loadCorpus('../corpus/load-corpus.ndjson');
const INTAKE_URL = __ENV.INTAKE_URL || 'http://localhost:8080/v1/instructions';

const acceptedTotal = new Counter('app_accepted_total');
const rejectedTotal = new Counter('app_rejected_total');

export const options = {
  scenarios: {
    sustained_250: {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 400,
      stages: [
        { target: 50, duration: '30s' },
        { target: 100, duration: '30s' },
        { target: 150, duration: '30s' },
        { target: 200, duration: '30s' },
        { target: 250, duration: '30s' },
        { target: 250, duration: '15m' }, // sustain
        { target: 0, duration: '1m' },     // ramp down
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const row = corpus[Math.floor(Math.random() * corpus.length)];
  const xml = materialize(row, __VU, __ITER);
  const res = http.post(INTAKE_URL, xml, { headers: { 'Content-Type': 'application/xml' } });
  const ok = check(res, { 'status is 2xx': (r) => r.status >= 200 && r.status < 300 });
  if (ok) {
    acceptedTotal.add(1);
  } else {
    rejectedTotal.add(1);
  }
}
