resource "aws_ecr_repository" "this" {
  for_each = toset(var.repositories)

  name = "${var.name}/${each.value}"

  # This benchmark tags by commit SHA (phase prompt §4), never `latest` --
  # immutability catches an accidental overwrite of a tag already deployed
  # mid-benchmark, which would otherwise silently change what's running
  # without any Terraform or kubectl action showing it.
  image_tag_mutability = "IMMUTABLE"

  force_delete = true # so `terraform destroy` doesn't fail on a non-empty repo -- see infra/README.md's cost-control section
}

# Expire untagged images after a day -- a benchmark that gets re-applied a
# few times during Step 2/3 (phase prompt §10) will otherwise accumulate
# dangling layers from replaced tags. Storage is cheap ($0.10/GB-month) but
# not a reason to let it grow unbounded for a stack meant to exist for
# hours.
resource "aws_ecr_lifecycle_policy" "expire_untagged" {
  for_each   = aws_ecr_repository.this
  repository = each.value.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Expire untagged images after 1 day"
      selection = {
        tagStatus   = "untagged"
        countType   = "sinceImagePushed"
        countUnit   = "days"
        countNumber = 1
      }
      action = { type = "expire" }
    }]
  })
}
