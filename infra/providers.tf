provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project   = "payment-instruction-service"
      Phase     = "13"
      Purpose   = "benchmark"
      ManagedBy = "terraform"
      DestroyBy = "same-day"
    }
  }
}

# Configured from the EKS module's own outputs rather than a static
# kubeconfig file, so `terraform apply` can create the cluster and then
# immediately deploy into it in one pass without a manual `aws eks
# update-kubeconfig` step in between.
provider "kubernetes" {
  host                   = module.eks.cluster_endpoint
  cluster_ca_certificate = base64decode(module.eks.cluster_certificate_authority_data)

  exec {
    api_version = "client.authentication.k8s.io/v1beta1"
    command     = "aws"
    args        = ["eks", "get-token", "--cluster-name", module.eks.cluster_name, "--region", var.aws_region]
  }
}

provider "helm" {
  kubernetes {
    host                   = module.eks.cluster_endpoint
    cluster_ca_certificate = base64decode(module.eks.cluster_certificate_authority_data)

    exec {
      api_version = "client.authentication.k8s.io/v1beta1"
      command     = "aws"
      args        = ["eks", "get-token", "--cluster-name", module.eks.cluster_name, "--region", var.aws_region]
    }
  }
}
