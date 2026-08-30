# Phase 13 infrastructure

Terraform to deploy this project to real AWS/EKS, for one purpose: answer
Phase 12 §5.2's open question — what does the application actually sustain
when the host itself is not the bottleneck. Phase 12 measured 250/sec on a
memory-constrained single laptop and explicitly could not validate the
500/sec target against capable hardware.

**As of this writing, this stack has not been applied.** Everything below
describes what `terraform apply` would create, not something currently
running. No `terraform init`, `plan`, `apply`, or `destroy` has been run
against this configuration. See "Status" at the bottom.

## What it provisions

| Module | Resource | Notes |
|---|---|---|
| `network` | VPC, 2 AZs, public+private subnets, 1 NAT gateway | Single NAT is a deliberate cost/availability tradeoff — see below |
| `eks` | EKS 1.31, managed node group (t3.large, 2–4 nodes), IRSA, AWS LB Controller | On-demand nodes; no spot, to avoid interruption noise during the benchmark |
| `rds` | PostgreSQL 16, db.t4g.medium, single-AZ | Backups/deletion-protection/final-snapshot all disabled — see "Cost controls" |
| `kafka` | MSK Serverless | See "Kafka" below for the choice and its cost |
| `ecr` | 6 repositories (5 services + k6 load image) | Immutable tags, force-delete, untagged images expire in 1 day |
| `secrets` | Secrets Store CSI driver + IRSA policy | DB credentials reach pods with no plaintext in any manifest |
| `observability` | Prometheus + Grafana (kube-prometheus-stack); Jaeger optional, off by default | See "Observability" below |

## Why local state

No S3 backend. `*.tfstate*` is already gitignored (root `.gitignore`), so
state never reaches version control regardless of backend. Provisioning
and locking an S3 bucket + DynamoDB table is real setup work — and its own
teardown liability — for a stack meant to exist for hours on one operator's
machine. If this stack were going to outlive a single benchmark session
or be run by more than one person, that calculus reverses immediately:
remote state with locking would become necessary, not optional.

## Kafka: MSK Serverless vs. Redpanda-on-EKS

Both were priced before choosing. Redpanda-on-EKS is cheaper in raw
dollars — it consumes node capacity already being paid for, with no
separate service charge. It was rejected anyway, for the same reason
Phase 12 §4.4.2 spent an entire section ruling out shared host-resource
contention as a confound: this phase exists to isolate the *application's*
ceiling. A self-run broker competing with the application for the same
node's CPU/memory reintroduces exactly the ambiguity that section worked
to eliminate on the laptop, and this session's own Part 3 experience
sizing Redpanda down to `--smp=2 --memory=1G` to fit a memory-constrained
host is direct evidence of how easily broker sizing becomes a variable in
its own right. MSK Serverless removes broker operation from the
experiment entirely, at a modest, bounded cost premium.

**Pricing basis** (checked live at write time, not from training data —
AWS list pricing changes and the numbers below have real budget stakes):

- MSK Serverless: $0.75/cluster-hour + $0.0015/partition-hour +
  $0.10/GB-month storage + $0.10/GB-in + $0.05/GB-out
- This project's own documented topic layout
  (`.notes/ARCHITECTURE.md`): 9 topics, 87 total partitions
  (6 topics × 12 + 2 topics × 6 + 1 dlq × 3)

**MSK Serverless base cost**: $0.75 + (87 × $0.0015) = **≈$0.88/hour**,
before any storage or data-transfer cost, which for a same-day benchmark's
data volume is negligible against that base rate. Well under the "cost
tolerable" bar this phase set for itself.

**Redpanda-on-EKS counterfactual**: no separate service charge, only the
node capacity it consumes — but that capacity is either (a) additional
node(s) beyond what the application needs, which is a real dollar cost
too and one this README would then have to estimate broker sizing for, or
(b) shared with the application's own pods, which is the confound this
phase is trying to avoid. Estimated at roughly the cost of one additional
t3.large (~$0.083/hour) if run as a dedicated node, i.e. cheaper than MSK
— but that comparison is the wrong one to optimize, given what the extra
$0.80/hour is buying.

**Decision: MSK Serverless.** ≈$0.88/hour is a small fraction of the
stack's total hourly cost (see table below) and buys a clean separation
between "the application's ceiling" and "whatever the broker is doing,"
which is the entire point of running this on real hardware.

**Structural consequence, named here rather than found by surprise in Step
2**: MSK Serverless supports exactly one authentication mode, SASL/IAM.
Every Kafka client in this codebase today —
`core/src/main/java/com/kishore/payments/core/outbox/OutboxProducerFactory.java`
and each service's `KafkaConsumerConfig` — configures a plain PLAINTEXT
connection with no SASL properties, because neither docker-compose's nor
kind's Redpanda has ever needed any. Connecting to MSK will require adding
the `software.amazon.msk:aws-msk-iam-auth` dependency to `core`'s POM and
setting `security.protocol=SASL_SSL` / `sasl.mechanism=AWS_MSK_IAM` on
every producer and consumer client. This is a genuinely new, justified
dependency under this project's own dependency-addition rule, not yet
implemented — deferred to the Step 2 (Deploy) session, and recorded as an
anticipated finding in `.notes/reports/PHASE-13-REPORT.md` rather than
something to report as a surprise when it's hit.

## Observability

Prometheus + Grafana (via the `prometheus-community/kube-prometheus-stack`
Helm chart) are always deployed; Phase 10's own dashboards are the
intended provisioned dashboards once Step 2 wires them in. Alertmanager is
disabled — Phase 12 §4.5 already established alert *rules* aren't this
project's gap, and a same-day benchmark has nowhere to route an alert
anyway. Prometheus retention is cut to 6 hours.

Jaeger is **off by default** (`enable_jaeger = false`). Phase 12 measured
tracing consuming real host memory, once outright OOM-killing Jaeger under
load (Part 1 §4.4.1). This phase's purpose is measuring the application's
own ceiling, not the application-plus-tracing-overhead ceiling, so the
default keeps tracing out of the measurement. The cost of that choice is
interpretability: without Jaeger, a benchmark run that shows elevated
per-stage latency has no trace-level view into where in a single
instruction's flow the time went — only the metrics this project already
emits (Phase 12's own dashboards) plus log correlation. Set
`enable_jaeger = true` to trade some of the application's own headroom for
that visibility; expect the sustained-rate number measured with it on to
mean something narrower than the one measured without it, and say so if
both are reported.

## Cost controls (non-negotiable, per the phase's own requirements)

Every resource that could otherwise block a clean `terraform destroy` is
disabled or zeroed at the Terraform level, not left to be remembered at
teardown time:

- RDS: `backup_retention_period = 0`, `deletion_protection = false`,
  `skip_final_snapshot = true`
- Secrets Manager: `recovery_window_in_days = 0` (immediate deletion, no
  30-day recovery window holding the secret open)
- ECR: `force_delete = true` on every repository (destroy doesn't fail on
  a non-empty repo)
- No S3 backend, no DynamoDB lock table — nothing outside the module
  graph itself to separately tear down

## Estimated hourly cost

| Resource | Rate | Qty | Hourly |
|---|---|---|---|
| EKS control plane | $0.10/hr flat | 1 | $0.10 |
| EC2 t3.large (nodes) | ~$0.0832/hr on-demand | 2 (min) – 4 (max) | $0.166 – $0.333 |
| RDS db.t4g.medium | ~$0.073/hr | 1 | $0.073 |
| RDS storage (gp3) | $0.08/GB-mo → ~$0.00011/GB-hr | 20GB | ~$0.002 |
| MSK Serverless (base) | $0.75/hr + $0.0015/partition-hr | 1 cluster, 87 partitions | $0.88 |
| NAT Gateway | $0.045/hr + $0.045/GB processed | 1 | $0.045 + data |
| ALB | $0.0252/hr + $0.008/LCU-hr | 1 (if provisioned by the LB controller) | ~$0.03 – $0.05 |
| EBS (node root + PVs) | $0.08/GB-mo → ~$0.00011/GB-hr | ~60GB across nodes | ~$0.007 |
| Secrets Manager | $0.40/secret/month → ~$0.00055/hr | 1 secret | ~$0.001 |
| **Total (2 nodes, no Jaeger)** | | | **≈$1.30/hour** |
| **Total (4 nodes, no Jaeger)** | | | **≈$1.47/hour** |

Jaeger, if enabled, adds negligible direct AWS cost (it runs on already-
provisioned node capacity as an all-in-one in-memory instance) but is
excluded from the "no Jaeger" totals above because that's the configuration
this benchmark is meant to run in.

**A full apply-benchmark-destroy day, generously budgeted at 6 hours end
to end, costs on the order of $8–9.** This is the number to hold this
plan accountable to once real usage is recorded — see "Status" below.

## Apply / destroy

Not yet run. When run:

```
cd infra
terraform init
terraform plan   # inspect before applying anything
terraform apply
```

**Before anything expensive exists**, verify `terraform destroy` actually
tears down cleanly on a throwaway minimal apply — this has NOT been done
yet (see "Status"). Do that first, every time, not just once historically:
infrastructure code drifts, and a destroy that worked on a prior version
of this configuration is not evidence about the current one.

Full teardown:

```
make destroy
```

`make destroy` runs `terraform destroy -auto-approve` and then
`scripts/post-destroy-check.sh` to list anything left behind that
Terraform doesn't know about (orphaned EBS volumes, load balancers,
Elastic IPs are the usual culprits when a controller — e.g. the AWS LB
Controller — created a resource outside Terraform's own state).

## Status

- Terraform: written, formatted (`terraform fmt -recursive -check` passes
  clean), not initialized, not planned, not applied.
- `terraform destroy` verification (acceptance criterion 1: verify destroy
  works on a throwaway minimal apply *before* creating anything expensive):
  **not done.** AWS credentials available in the environment this was
  written in returned `InvalidClientTokenId`. This must be the first live
  action taken against this configuration, before any full apply — not
  assumed to still hold from a prior version of this code, and not skipped
  because the configuration "looks right."
- Cost estimate and the MSK/Redpanda comparison above: from live pricing
  research and this project's own documented partition layout, not from a
  live apply — treat the ≈$1.30–1.47/hour figures as an estimate to be
  checked against actual billing once a real run happens, not as
  measured fact.
- No apply, deploy, benchmark, or teardown has happened. See
  `.notes/reports/PHASE-13-REPORT.md` for the full record of what this
  session did and did not do, and why.
