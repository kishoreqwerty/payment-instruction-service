// Phase 12 §3: ramp to 500/sec over 5 minutes (stepped, so degradation onset is visible rather
// than only its aftermath), sustain 500/sec for 30 minutes, ramp down. Against the real
// intake-service REST endpoint, not the null endpoint -- generator-ceiling.js already showed the
// harness itself clears 500/sec by two orders of magnitude, so this measures the pipeline.
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
    sustained_500: {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 800,
      stages: [
        // Stepped ramp: five 1-minute steps rather than one smooth 5-minute ramp, so a
        // Grafana/Prometheus query against payment_pipeline_stage_duration_seconds during the
        // run can show latency at each step distinctly rather than one continuous blur.
        { target: 100, duration: '1m' },
        { target: 200, duration: '1m' },
        { target: 300, duration: '1m' },
        { target: 400, duration: '1m' },
        { target: 500, duration: '1m' },
        { target: 500, duration: '30m' }, // sustain
        { target: 0, duration: '2m' },     // ramp down
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
