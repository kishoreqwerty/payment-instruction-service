output "bootstrap_brokers_sasl_iam" {
  value = aws_msk_serverless_cluster.this.bootstrap_brokers_sasl_iam
}

output "cluster_arn" {
  value = aws_msk_serverless_cluster.this.arn
}

output "client_irsa_role_arn" {
  value = module.msk_client_irsa.iam_role_arn
}

output "client_irsa_role_name" {
  description = "Same role as client_irsa_role_arn, by name -- the secrets module attaches a Secrets Manager policy onto this same role rather than creating a second IRSA role, since a Kubernetes service account's IRSA annotation can only point at one IAM role at a time."
  value       = module.msk_client_irsa.iam_role_name
}
