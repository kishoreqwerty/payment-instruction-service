variable "enable_jaeger" {
  type = bool
}

# Depended on implicitly via the helm provider being configured against
# the live cluster (providers.tf) -- no explicit cluster reference needed
# here, but declared so this module's intent (it deploys *into* the
# cluster the eks module created) isn't left implicit.
variable "cluster_name" {
  type = string
}
