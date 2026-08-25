#!/usr/bin/env bash
# Phase 12 §1: "Capture, per run: host memory pressure, swap, Docker's allocation,
# container count, and what else is running." Called before and after each run --
# see PHASE-12-REPORT.md for why after matters as much as before.
set -euo pipefail

LABEL="${1:-baseline}"
OUT_DIR="../env-baselines"
mkdir -p "$OUT_DIR"
OUT_FILE="$OUT_DIR/${LABEL}-$(date +%Y%m%dT%H%M%S).txt"

{
  echo "=== $(date -u +%Y-%m-%dT%H:%M:%SZ) — $LABEL ==="
  echo
  echo "--- host memory (vm_stat) ---"
  vm_stat
  echo
  echo "--- host swap (sysctl vm.swapusage) ---"
  sysctl vm.swapusage 2>/dev/null || echo "unavailable"
  echo
  echo "--- host load ---"
  uptime
  echo
  echo "--- docker info: memory / cpu allocation ---"
  docker info --format 'CPUs: {{.NCPU}}  Memory: {{.MemTotal}}' 2>/dev/null || echo "docker info unavailable"
  echo
  echo "--- docker stats (one shot, all running containers) ---"
  docker stats --no-stream 2>/dev/null || echo "no containers running / docker stats unavailable"
  echo
  echo "--- docker ps (running container count and names) ---"
  docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}' 2>/dev/null || echo "docker ps unavailable"
  echo
  echo "--- other notable processes (java, node, k6) ---"
  ps aux | grep -E 'java|node|k6' | grep -v grep || echo "none"
} > "$OUT_FILE"

echo "Wrote $OUT_FILE"
cat "$OUT_FILE"
