// Phase 12 §3: "Run a second profile that ramps until something breaks. The number that matters
// is where it degrades and why, not that it met a target chosen months ago." Climbs in 90s steps
// well past 500/sec; no upper target is "correct" here, the point is to find where it stops being
// true rather than to hit a number. http_req_failed is NOT a threshold that aborts the run --
// degradation is the expected, wanted outcome, not a failure to short-circuit away from.
//
// maxVUs raised from 3000 to 10000 (PHASE-12-REPORT.md section 5): the first run found no hard
// system-side failure up to a 3000/sec target -- every completed request returned 2xx, latency
// grew to p95 ~3s under queueing, and 278,382 of ~900,000 attempted iterations were *dropped* by
// k6 itself because 3000 VUs could not keep dispatching at the requested rate once individual
// requests started taking seconds. That run measured k6's own configured ceiling, not the
// system's -- the true failure mode, if the system has one below where this now tops out, was
// never observed. Stages extended past the old 3000 top end for the same reason: 3000 wasn't a
// ceiling, it was where the harness stopped looking.
import http from 'k6/http';
import { check } from 'k6';
import { loadCorpus, materialize } from './lib/corpus.js';

const corpus = loadCorpus('../corpus/load-corpus.ndjson');
const INTAKE_URL = __ENV.INTAKE_URL || 'http://localhost:8080/v1/instructions';

export const options = {
  scenarios: {
    break_test: {
      executor: 'ramping-arrival-rate',
      startRate: 500,
      timeUnit: '1s',
      preAllocatedVUs: 2000,
      maxVUs: 10000,
      stages: [
        { target: 750, duration: '90s' },
        { target: 1000, duration: '90s' },
        { target: 1500, duration: '90s' },
        { target: 2000, duration: '90s' },
        { target: 2500, duration: '90s' },
        { target: 3000, duration: '90s' },
        { target: 4000, duration: '90s' },
        { target: 5000, duration: '90s' },
        { target: 6000, duration: '90s' },
        { target: 0, duration: '30s' },
      ],
    },
  },
};

export default function () {
  const row = corpus[Math.floor(Math.random() * corpus.length)];
  const xml = materialize(row, __VU, __ITER);
  const res = http.post(INTAKE_URL, xml, { headers: { 'Content-Type': 'application/xml' } });
  check(res, { 'status is 2xx': (r) => r.status >= 200 && r.status < 300 });
}
