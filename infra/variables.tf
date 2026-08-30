variable "aws_region" {
  description = "us-east-2 (Ohio): cheaper than us-east-1 for EC2 in most instance families, and this stack has no reason to prefer any one region over another beyond cost."
  type        = string
  default     = "us-east-2"
}

variable "cluster_name" {
  description = "EKS cluster name. Kept short and dated in the README's own apply command, not baked in here, so re-applies on a different day don't collide with a stale name."
  type        = string
  default     = "payments-bench"
}

variable "node_instance_type" {
  type    = string
  default = "t3.large"
}

variable "node_min_size" {
  type    = number
  default = 2
}

variable "node_max_size" {
  type    = number
  default = 4
}

# desired_size starts at min; the benchmark's own ramp (§5 of the phase
# prompt) is what should push the cluster autoscaler (or manual scaling,
# if the autoscaler add-on is skipped -- see modules/eks) toward max, not
# a large desired_size chosen up front that pays for capacity before any
# load exists to justify it.
variable "node_desired_size" {
  type    = number
  default = 2
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.medium"
}

variable "db_engine_version" {
  type    = string
  default = "16.4"
}

variable "kafka_partitions_per_topic" {
  description = "Matches .notes/ARCHITECTURE.md's documented partition counts exactly -- see modules/kafka for the per-topic breakdown. Exposed as a variable rather than hardcoded so a topic-creation script and the MSK capacity math in infra/README.md stay derived from one place."
  type        = map(number)
  default = {
    "payments.received"   = 12
    "payments.validated"  = 12
    "payments.enriched"   = 12
    "payments.routed"     = 12
    "payments.sent"       = 12
    "payments.settled"    = 12
    "payments.exceptions" = 6
    "payments.repaired"   = 6
    "payments.dlq"        = 3
  }
}

variable "enable_jaeger" {
  description = "Off by default -- see infra/README.md 'Observability' for why: Phase 12 measured Jaeger consuming real host memory (and getting OOM-killed once, Part 1 §4.4.1), and this phase's whole purpose is measuring the application's own ceiling, not the application-plus-tracing-overhead ceiling. Set true explicitly to include it, and expect the benchmark's own number to mean something narrower if you do."
  type        = bool
  default     = false
}
