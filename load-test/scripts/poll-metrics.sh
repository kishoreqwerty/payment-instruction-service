#!/usr/bin/env bash
# Phase 12 §3: "Watch during the run" -- consumer lag (the derivative matters more than the
# value), per-stage latency, outbox oldest-pending -- sampled repeatedly across the run, not
# read once at the end.
#
# Consumer lag and outbox-pending come from direct broker/database queries, not Prometheus:
# processing-service (which owns the VALIDATION/ENRICHMENT/ROUTING consumers and their lag) has
# no reachable /actuator/prometheus in this "host process + docker-compose infra" run mode -- see
# PHASE-12-REPORT.md §1.6. rpk's own group-describe and a direct SQL query against core.outbox
# are broker/database truth regardless of whether the app's own Micrometer registry is scrapable.
set -uo pipefail
OUT="${1:-../results/metrics-timeline.tsv}"
PROM="http://localhost:9090"

q_prom() {
  curl -s --get "$PROM/api/v1/query" --data-urlencode "query=$1" | python3 -c "
import sys, json
d = json.load(sys.stdin)
r = d.get('data', {}).get('result', [])
print('NA' if not r else round(float(r[0]['value'][1]), 4))
"
}

total_lag() {
  docker exec payment-instruction-service-redpanda-1 rpk group describe processing-service --brokers localhost:9092 2>/dev/null \
    | awk '/TOTAL-LAG/ {print $2}'
}

oldest_pending_seconds() {
  docker exec payment-instruction-service-postgres-1 psql -U payments -d payments -t -c \
    "SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at))), 0) FROM core.outbox WHERE published_at IS NULL;" 2>/dev/null \
    | tr -d ' \n'
}

pending_count() {
  docker exec payment-instruction-service-postgres-1 psql -U payments -d payments -t -c \
    "SELECT COUNT(*) FROM core.outbox WHERE published_at IS NULL;" 2>/dev/null | tr -d ' \n'
}

if [ ! -f "$OUT" ]; then
  echo -e "timestamp\tprocessing_consumer_total_lag\toutbox_pending_count\toutbox_oldest_pending_s\tgateway_p95_dispatch_s\trail_simulator_up" > "$OUT"
fi

while true; do
  ts=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  lag=$(total_lag)
  pending=$(pending_count)
  oldest=$(oldest_pending_seconds)
  gw_p95=$(q_prom 'histogram_quantile(0.95, sum(rate(payment_dispatch_duration_seconds_bucket[1m])) by (le))')
  rail_up=$(q_prom 'up{job="rail-simulator"}')
  echo -e "${ts}\t${lag}\t${pending}\t${oldest}\t${gw_p95}\t${rail_up}" | tee -a "$OUT"
  sleep 60
done
