output "cluster_name" {
  value = module.eks.cluster_name
}

output "configure_kubectl" {
  description = "Run this after apply to point kubectl at the new cluster."
  value       = "aws eks update-kubeconfig --name ${module.eks.cluster_name} --region ${var.aws_region}"
}

output "rds_address" {
  value = module.rds.address
}

output "rds_secret_arn" {
  description = "Secrets Manager ARN holding DB host/port/username/password/jdbc_url. Never read the value into Terraform output -- fetch it via `aws secretsmanager get-secret-value` or through the in-cluster CSI mount instead."
  value       = module.rds.secret_arn
}

output "msk_bootstrap_brokers_sasl_iam" {
  value = module.kafka.bootstrap_brokers_sasl_iam
}

output "msk_cluster_arn" {
  value = module.kafka.cluster_arn
}

output "ecr_repository_urls" {
  value = module.ecr.repository_urls
}

output "app_irsa_role_arn" {
  description = "The one IRSA role app pods (service account payments:payments-msk-client) assume for both MSK IAM auth and DB-secret CSI reads."
  value       = module.kafka.client_irsa_role_arn
}

output "grafana_port_forward" {
  description = "Grafana has no public endpoint by design (no extra ALB/ingress for a same-day benchmark) -- reach it via port-forward."
  value       = "kubectl -n ${module.observability.namespace} port-forward svc/${module.observability.grafana_service} 3000:80"
}
