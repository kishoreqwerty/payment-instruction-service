# Secrets Store CSI driver + AWS provider, installed cluster-wide via
# Helm -- this is how the DB credentials reach app pods with no plaintext
# in any manifest and nothing written to committed state: the driver mounts
# the live Secrets Manager value as a file at pod-start time, using the
# pod's own IRSA identity to read it.
resource "helm_release" "csi_driver" {
  name       = "csi-secrets-store"
  repository = "https://kubernetes-sigs.github.io/secrets-store-csi-driver/charts"
  chart      = "secrets-store-csi-driver"
  version    = "1.4.6"
  namespace  = "kube-system"

  set {
    name  = "syncSecret.enabled"
    value = "true"
  }
}

resource "helm_release" "csi_driver_provider_aws" {
  name       = "secrets-provider-aws"
  repository = "https://aws.github.io/secrets-store-csi-driver-provider-aws"
  chart      = "secrets-store-csi-driver-provider-aws"
  version    = "0.3.9"
  namespace  = "kube-system"

  depends_on = [helm_release.csi_driver]
}

data "aws_iam_policy_document" "db_secret_read" {
  statement {
    sid       = "ReadDbSecret"
    actions   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
    resources = [var.db_secret_arn]
  }
}

resource "aws_iam_policy" "db_secret_read" {
  name   = "${var.name}-db-secret-read"
  policy = data.aws_iam_policy_document.db_secret_read.json
}

resource "aws_iam_role_policy_attachment" "db_secret_read" {
  role       = var.client_irsa_role_name
  policy_arn = aws_iam_policy.db_secret_read.arn
}
