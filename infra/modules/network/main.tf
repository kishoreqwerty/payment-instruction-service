data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, 2)
}

# terraform-aws-modules/vpc is used here rather than hand-written
# aws_subnet/aws_route_table/aws_nat_gateway resources -- it is the
# standard, widely-used way to get AZ-aware CIDR math, the EKS
# discovery tags (kubernetes.io/role/elb etc.) right, and a NAT gateway
# wired up correctly, without several hundred lines of easy-to-get-wrong
# plumbing that would add risk without adding anything specific to this
# project.
module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.13"

  name = var.name
  cidr = var.vpc_cidr
  azs  = local.azs

  # Two AZs, public subnets for the ALB, private subnets for nodes and
  # data -- .notes/ARCHITECTURE.md §7.2 and the phase prompt both specify
  # this shape directly.
  public_subnets  = [for i, az in local.azs : cidrsubnet(var.vpc_cidr, 4, i)]
  private_subnets = [for i, az in local.azs : cidrsubnet(var.vpc_cidr, 4, i + 4)]

  # A single NAT gateway, not one per AZ -- a deliberate cost/availability
  # tradeoff for a stack meant to live for hours, not a production
  # posture. One NAT means a single point of failure for private-subnet
  # egress (ECR pulls, AWS API calls from pods) if its AZ has a problem;
  # for a same-day benchmark that risk is worth roughly two-thirds of a
  # second NAT gateway's hourly cost (infra/README.md's cost table). A
  # longer-lived environment should use one NAT per AZ instead.
  enable_nat_gateway     = true
  single_nat_gateway     = true
  one_nat_gateway_per_az = false

  enable_dns_hostnames = true
  enable_dns_support   = true

  # Required for the EKS module's own subnet auto-discovery and for the
  # AWS Load Balancer Controller to find the right subnets for an
  # internet-facing ALB versus internal node/pod networking.
  public_subnet_tags = {
    "kubernetes.io/role/elb"            = "1"
    "kubernetes.io/cluster/${var.name}" = "shared"
  }
  private_subnet_tags = {
    "kubernetes.io/role/internal-elb"   = "1"
    "kubernetes.io/cluster/${var.name}" = "shared"
  }
}
