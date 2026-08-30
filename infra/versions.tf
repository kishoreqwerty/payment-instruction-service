terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.31"
    }
    helm = {
      source  = "hashicorp/helm"
      version = "~> 2.14"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
    http = {
      source  = "hashicorp/http"
      version = "~> 3.4"
    }
  }

  # Local state, deliberately -- see infra/README.md "Why local state" for
  # the reasoning. Provisioning and locking an S3 backend (plus a DynamoDB
  # lock table) is real setup work for a stack meant to exist for hours,
  # and that setup would itself need tearing down afterward. *.tfstate* is
  # already gitignored (root .gitignore), so state never reaches version
  # control regardless of backend.
}
