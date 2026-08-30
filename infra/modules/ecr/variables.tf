variable "name" {
  type = string
}

variable "repositories" {
  description = "One ECR repo per image built in Part 3's own Dockerfiles (k8s/../Dockerfile, load-test/Dockerfile) -- same five services plus the k6 load image, nothing added or renamed."
  type        = list(string)
  default = [
    "intake-service",
    "processing-service",
    "settlement-gateway",
    "exception-service",
    "rail-simulator",
    "k6-load",
  ]
}
