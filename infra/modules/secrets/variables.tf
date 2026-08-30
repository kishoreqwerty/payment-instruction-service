variable "name" {
  type = string
}

# The app-service-account IRSA role, created by the kafka module. A
# Kubernetes service account's IRSA annotation points at exactly one IAM
# role, so DB-secret read access is added to that same role rather than
# creating a second IRSA role for the same service account (payments:
# payments-msk-client) -- two roles trusting the same SA would leave only
# one of them actually reachable from a pod.
variable "client_irsa_role_name" {
  type = string
}

variable "db_secret_arn" {
  description = "ARN of the RDS module's Secrets Manager secret. Scopes access to exactly this secret -- nothing broader."
  type        = string
}
