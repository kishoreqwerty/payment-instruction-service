resource "kubernetes_namespace" "observability" {
  metadata {
    name = "observability"
  }
}

# kube-prometheus-stack, not hand-rolled Prometheus/Grafana manifests --
# the same reasoning as the network/eks modules: this chart already
# solves service discovery, scrape config, and Grafana provisioning
# correctly, and this project's own Phase 10 dashboards
# (observability/grafana/dashboards/*.json) are mounted into it as
# provisioned dashboards rather than re-created.
resource "helm_release" "kube_prometheus_stack" {
  name       = "kube-prometheus-stack"
  repository = "https://prometheus-community.github.io/helm-charts"
  chart      = "kube-prometheus-stack"
  version    = "65.5.1"
  namespace  = kubernetes_namespace.observability.metadata[0].name

  # Deliberately minimal: this cluster exists to run one benchmark, not to
  # host a general-purpose monitoring stack. Alertmanager is off -- Phase
  # 12 §4.5 already established that alert *rules* aren't the gap this
  # project has (OutboxStalled fired correctly the whole time); nothing in
  # a same-day benchmark needs alerts routed anywhere.
  values = [
    yamlencode({
      alertmanager = { enabled = false }
      grafana = {
        adminPassword = "payments-bench" # throwaway cluster, torn down same day -- see infra/README.md
      }
      prometheus = {
        prometheusSpec = {
          retention = "6h" # a same-day benchmark has no use for Prometheus's own default 10-day retention
          additionalScrapeConfigs = [
            {
              job_name              = "payments-services"
              kubernetes_sd_configs = [{ role = "pod", namespaces = { names = ["payments"] } }]
              relabel_configs = [
                {
                  source_labels = ["__meta_kubernetes_pod_annotation_prometheus_io_scrape"]
                  action        = "keep"
                  regex         = "true"
                },
                {
                  source_labels = ["__meta_kubernetes_pod_annotation_prometheus_io_path"]
                  action        = "replace"
                  target_label  = "__metrics_path__"
                  regex         = "(.+)"
                },
              ]
            }
          ]
        }
      }
    })
  ]
}

# Off by default -- var.enable_jaeger (infra/README.md "Observability" has
# the full reasoning: Phase 12 measured tracing consuming real resources,
# once outright OOM-killing Jaeger under load (Part 1 §4.4.1), and this
# phase's own purpose is measuring the application's ceiling, not the
# application-plus-tracing-overhead ceiling). If enabled, this is a single
# all-in-one instance -- adequate for a benchmark's own trace volume, not
# a production tracing deployment.
resource "helm_release" "jaeger" {
  count = var.enable_jaeger ? 1 : 0

  name       = "jaeger"
  repository = "https://jaegertracing.github.io/helm-charts"
  chart      = "jaeger"
  version    = "3.4.1"
  namespace  = kubernetes_namespace.observability.metadata[0].name

  values = [
    yamlencode({
      provisionDataStore = { cassandra = false, elasticsearch = false }
      allInOne           = { enabled = true }
      storage            = { type = "memory" }
      agent              = { enabled = false }
      collector          = { enabled = false }
      query              = { enabled = false }
    })
  ]
}
