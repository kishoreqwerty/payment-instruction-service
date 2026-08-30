resource "random_password" "db" {
  length  = 32
  special = false # avoids characters that need escaping in a JDBC URL or a Secrets Manager JSON value
}

resource "aws_db_subnet_group" "this" {
  name       = "${var.name}-db"
  subnet_ids = var.private_subnet_ids
}

resource "aws_security_group" "rds" {
  name_prefix = "${var.name}-rds-"
  description = "Postgres access from EKS nodes only, not the whole VPC."
  vpc_id      = var.vpc_id

  ingress {
    description     = "Postgres from EKS nodes"
    from_port       = 5432
    to_port         = 5432
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

# Same schema/user/password docker-compose.yml and the kind manifests
# already use ("payments"/"payments") -- app config only needs to change
# host and password, not database or username, when pointed at RDS.
resource "aws_db_instance" "this" {
  identifier     = "${var.name}-postgres"
  engine         = "postgres"
  engine_version = var.engine_version
  instance_class = var.instance_class

  allocated_storage     = 20
  max_allocated_storage = 50 # storage autoscaling cap, cheap insurance against a benchmark filling the disk with WAL/logs mid-run
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name  = "payments"
  username = "payments"
  password = random_password.db.result
  port     = 5432

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.rds.id]

  # Phase prompt §2, verbatim: single-AZ, backups disabled, deletion
  # protection off, skip final snapshot. All three exist so `terraform
  # destroy` cannot be blocked at 1am with a running bill -- a snapshot
  # request or deletion-protection flag left on is exactly the kind of
  # thing that turns a same-day teardown into a surprise multi-day one.
  multi_az                = false
  backup_retention_period = 0
  deletion_protection     = false
  skip_final_snapshot     = true

  apply_immediately = true # no maintenance window to wait through on a stack meant to live for hours
}

resource "aws_secretsmanager_secret" "db" {
  name                    = "${var.name}/db-credentials"
  recovery_window_in_days = 0 # immediate deletion on destroy, same reasoning as skip_final_snapshot above -- see infra/README.md's cost-control section
}

resource "aws_secretsmanager_secret_version" "db" {
  secret_id = aws_secretsmanager_secret.db.id
  secret_string = jsonencode({
    host     = aws_db_instance.this.address
    port     = aws_db_instance.this.port
    dbname   = aws_db_instance.this.db_name
    username = aws_db_instance.this.username
    password = random_password.db.result
    # The exact form each service's application.yml already expects
    # (stringtype=unspecified for the InstructionState enum cast -- see
    # k8s-eks/*.yaml's own SPRING_DATASOURCE_URL override).
    jdbc_url = "jdbc:postgresql://${aws_db_instance.this.address}:${aws_db_instance.this.port}/${aws_db_instance.this.db_name}?stringtype=unspecified"
  })
}
