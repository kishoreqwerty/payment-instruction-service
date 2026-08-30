output "namespace" {
  value = kubernetes_namespace.observability.metadata[0].name
}

output "grafana_service" {
  description = "In-cluster service name for the Grafana UI, for the port-forward command documented in infra/README.md."
  value       = "kube-prometheus-stack-grafana"
}
