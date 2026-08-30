module "network" {
  source = "./modules/network"

  name   = var.cluster_name
  region = var.aws_region
}

module "eks" {
  source = "./modules/eks"

  name               = var.cluster_name
  vpc_id             = module.network.vpc_id
  private_subnet_ids = module.network.private_subnet_ids
  public_subnet_ids  = module.network.public_subnet_ids
  instance_type      = var.node_instance_type
  min_size           = var.node_min_size
  max_size           = var.node_max_size
  desired_size       = var.node_desired_size
}

module "rds" {
  source = "./modules/rds"

  name                   = var.cluster_name
  vpc_id                 = module.network.vpc_id
  private_subnet_ids     = module.network.private_subnet_ids
  instance_class         = var.db_instance_class
  engine_version         = var.db_engine_version
  node_security_group_id = module.eks.node_security_group_id
}

module "kafka" {
  source = "./modules/kafka"

  name                   = var.cluster_name
  vpc_id                 = module.network.vpc_id
  private_subnet_ids     = module.network.private_subnet_ids
  node_security_group_id = module.eks.node_security_group_id
  oidc_provider_arn      = module.eks.oidc_provider_arn
}

module "ecr" {
  source = "./modules/ecr"

  name = var.cluster_name
}

module "secrets" {
  source = "./modules/secrets"

  name                  = var.cluster_name
  client_irsa_role_name = module.kafka.client_irsa_role_name
  db_secret_arn         = module.rds.secret_arn
}

module "observability" {
  source = "./modules/observability"

  cluster_name  = module.eks.cluster_name
  enable_jaeger = var.enable_jaeger

  depends_on = [module.eks]
}
