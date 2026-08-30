# MSK Serverless chosen over a Redpanda StatefulSet on EKS -- see
# infra/README.md "Kafka: MSK Serverless vs. Redpanda-on-EKS" for the full
# pricing comparison and reasoning. In short: base cost for this project's
# own 9-topic, 87-partition layout (.notes/ARCHITECTURE.md's documented
# partition counts) prices out to well under $1/hour, "cost tolerable" by
# the phase's own standard, and it removes broker sizing/operation from the
# variables this benchmark is trying to isolate -- the whole point of this
# phase is measuring the application's own ceiling, and a self-run broker
# competing with the application for node capacity would reintroduce
# exactly the kind of shared-resource confound Phase 12 spent an entire
# section (§4.4.2) ruling out on the laptop.
resource "aws_security_group" "msk" {
  name_prefix = "${var.name}-msk-"
  description = "MSK Serverless IAM-auth port (9098) from EKS nodes only."
  vpc_id      = var.vpc_id

  ingress {
    description     = "Kafka (IAM auth) from EKS nodes"
    from_port       = 9098
    to_port         = 9098
    protocol        = "tcp"
    security_groups = [var.node_security_group_id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_msk_serverless_cluster" "this" {
  cluster_name = "${var.name}-msk"

  vpc_config {
    subnet_ids         = var.private_subnet_ids
    security_group_ids = [aws_security_group.msk.id]
  }

  # MSK Serverless supports exactly one authentication mode: SASL/IAM.
  # There is no plaintext or username/password option -- every client
  # (the app services, and the one-shot topic-creation job) authenticates
  # as an IAM principal. This is the structural difference the phase
  # prompt itself asked to be named rather than discovered by surprise:
  # every Kafka client in this codebase today (core's OutboxProducerFactory,
  # and each service's KafkaConsumerConfig) configures a plain PLAINTEXT
  # connection with no SASL properties at all, because docker-compose's and
  # kind's own Redpanda have never needed any. Connecting to MSK will need
  # security.protocol=SASL_SSL, sasl.mechanism=AWS_MSK_IAM, and the
  # software.amazon.msk:aws-msk-iam-auth library on the classpath -- a new,
  # genuinely-needed dependency for `core`, not yet added, named here in
  # advance rather than found mid-deploy. See infra/README.md's own note
  # on this and .notes/reports/PHASE-13-REPORT.md §5.
  client_authentication {
    sasl {
      iam {
        enabled = true
      }
    }
  }
}

# One IRSA role for both the topic-creation job and every app service's
# Kafka client -- kafka-cluster:* scoped to this one cluster's ARN, not a
# wildcard across any MSK cluster in the account.
data "aws_iam_policy_document" "msk_client" {
  statement {
    sid = "MskClusterConnect"
    actions = [
      "kafka-cluster:Connect",
      "kafka-cluster:AlterCluster",
      "kafka-cluster:DescribeCluster",
    ]
    resources = [aws_msk_serverless_cluster.this.arn]
  }

  statement {
    sid = "MskTopicLifecycle"
    actions = [
      "kafka-cluster:*Topic*",
      "kafka-cluster:WriteData",
      "kafka-cluster:ReadData",
    ]
    resources = ["arn:aws:kafka:*:*:topic/${var.name}-msk/*"]
  }

  statement {
    sid = "MskConsumerGroup"
    actions = [
      "kafka-cluster:AlterGroup",
      "kafka-cluster:DescribeGroup",
    ]
    resources = ["arn:aws:kafka:*:*:group/${var.name}-msk/*"]
  }
}

resource "aws_iam_policy" "msk_client" {
  name   = "${var.name}-msk-client"
  policy = data.aws_iam_policy_document.msk_client.json
}

module "msk_client_irsa" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version = "~> 5.48"

  role_name = "${var.name}-msk-client"

  oidc_providers = {
    main = {
      provider_arn = var.oidc_provider_arn
      # One shared service account name across every namespace/workload
      # that needs Kafka access -- app Deployments and the topic-creation
      # Job both use `payments-msk-client` in the `payments` namespace.
      namespace_service_accounts = ["payments:payments-msk-client"]
    }
  }
}

resource "aws_iam_role_policy_attachment" "msk_client" {
  role       = module.msk_client_irsa.iam_role_name
  policy_arn = aws_iam_policy.msk_client.arn
}
