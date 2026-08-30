variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "node_security_group_id" {
  type = string
}

variable "oidc_provider_arn" {
  description = "For the IRSA role app pods and the topic-creation job assume to authenticate to MSK via IAM -- MSK Serverless has no other auth mode."
  type        = string
}
