# terraform-aws-modules/eks handles the OIDC provider (required for IRSA),
# the cluster IAM role, and the managed node group's own launch template --
# the same reasoning as the network module: this is well-trodden,
# community-standard plumbing, not something specific to this project.
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.31"

  cluster_name = var.name

  # Checked against a live search at write time (August 2026); EKS's own
  # supported-version list moves roughly three times a year, so this will
  # be stale eventually -- confirm `aws eks describe-addon-versions` or the
  # EKS console lists this version before applying, and bump it if not.
  cluster_version = "1.31"

  cluster_endpoint_public_access  = true
  cluster_endpoint_private_access = true

  vpc_id     = var.vpc_id
  subnet_ids = var.private_subnet_ids

  # Public subnets carry only the ALB (created by the AWS Load Balancer
  # Controller, not this module) -- nodes and pods live in private subnets
  # with the network module's single NAT gateway for egress.
  control_plane_subnet_ids = var.private_subnet_ids

  enable_irsa = true

  eks_managed_node_groups = {
    default = {
      instance_types = [var.instance_type]
      capacity_type  = "ON_DEMAND"

      min_size     = var.min_size
      max_size     = var.max_size
      desired_size = var.desired_size

      subnet_ids = var.private_subnet_ids
    }
  }

  # This project's own workloads only, not a general-purpose cluster --
  # nothing here needs cluster-admin access from outside Terraform/kubectl
  # run by whoever applies this.
  enable_cluster_creator_admin_permissions = true
}

# The AWS Load Balancer Controller (needed for the intake ALB, §7.2) needs
# its own IRSA role with the AWS-published IAM policy for it -- created
# here, alongside the cluster, rather than as a loose resource in the root
# module, since it's tightly coupled to this cluster's own OIDC provider.
data "http" "lb_controller_iam_policy" {
  url = "https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/v2.9.0/docs/install/iam_policy.json"
}

resource "aws_iam_policy" "lb_controller" {
  name   = "${var.name}-aws-load-balancer-controller"
  policy = data.http.lb_controller_iam_policy.response_body
}

module "lb_controller_irsa" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version = "~> 5.48"

  role_name = "${var.name}-lb-controller"

  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["kube-system:aws-load-balancer-controller"]
    }
  }
}

resource "aws_iam_role_policy_attachment" "lb_controller" {
  role       = module.lb_controller_irsa.iam_role_name
  policy_arn = aws_iam_policy.lb_controller.arn
}
