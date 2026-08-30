variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "instance_class" {
  type = string
}

variable "engine_version" {
  type = string
}

variable "node_security_group_id" {
  description = "EKS node security group -- RDS's own security group allows inbound Postgres only from this, not from the whole VPC CIDR."
  type        = string
}
