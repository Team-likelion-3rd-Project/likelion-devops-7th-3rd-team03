# ── RDS용 보안그룹 ─────────────────────────────
resource "aws_security_group" "rds" {
  name        = "${var.cluster_name}-rds-sg"
  description = "Allow MySQL access from EKS nodes"
  vpc_id      = var.vpc_id

  ingress {
    description     = "MySQL from EKS nodes"
    from_port       = 3306
    to_port         = 3306
    protocol        = "tcp"
    security_groups = [var.node_security_group_id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.cluster_name}-rds-sg"
  }
}

# ── RDS 비밀번호 (Secrets Manager에 자동 저장) ───
resource "random_password" "rds_master" {
  length  = 24
  special = false # 일부 특수문자가 연결 문자열에서 문제되는 걸 피하기 위해 단순화
}

resource "aws_secretsmanager_secret" "rds_password" {
  name = "${var.cluster_name}/rds/app-password"
}

resource "aws_secretsmanager_secret_version" "rds_password" {
  secret_id     = aws_secretsmanager_secret.rds_password.id
  secret_string = random_password.rds_master.result
}

#── RDS APP 비밀번호 (Secrets Manager에 자동 저장) ───
resource "random_password" "rds_app_user" {
  length = 20
  special = false
}

resource "aws_secretsmanager_secret" "rds_app_password" {
  name = "${var.cluster_name}/rds/app-user-password"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "rds_app_password" {
  secret_id = aws_secretsmanager_secret.rds_app_password.id
  secret_string = random_password.rds_app_user.result
}

# ── RDS 인스턴스 ───────────────────────────────
# Multi-AZ로 이중화 (Primary + Standby, 동기 복제)
resource "aws_db_instance" "main" {
  identifier     = "${var.cluster_name}-db"
  engine         = "mysql"
  engine_version = var.rds_engine_version
  instance_class = var.rds_instance_class

  allocated_storage     = var.rds_allocated_storage
  max_allocated_storage = var.rds_max_allocated_storage # 스토리지 오토스케일링 여유
  storage_type          = "gp3"

  db_name  = "snipy"
  username = "app_admin"
  password = random_password.rds_master.result
  port     = 3306

  db_subnet_group_name   = var.database_subnet_group_name
  vpc_security_group_ids = [aws_security_group.rds.id]

  multi_az = var.rds_multi_az # Primary(AZ-A) + Standby(AZ-B), 동기 복제, 자동 페일오버

  backup_retention_period = var.rds_backup_retention_period # 최소한만 (짧은 프로젝트 기간)
  skip_final_snapshot     = true
  deletion_protection     = var.rds_deletion_protection # terraform destroy로 깔끔히 정리하기 위함

  publicly_accessible = false

  tags = {
    Name = "${var.cluster_name}-db"
  }
}

# ── ElastiCache용 보안그룹 ────────────────────
resource "aws_security_group" "elasticache" {
  name        = "${var.cluster_name}-elasticache-sg"
  description = "Allow Redis access from EKS nodes"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Redis from EKS nodes"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [var.node_security_group_id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.cluster_name}-elasticache-sg"
  }
}

# ── ElastiCache Replication Group ─────────────
# Cluster Mode: Disabled (단일 샤드), Primary 1 + Replica 1, Multi-AZ 자동 페일오버
resource "aws_elasticache_replication_group" "redis" {
  replication_group_id = "${var.cluster_name}-redis"
  description          = "Snipy Redis - Short URL Cache & Real-time Stats"

  engine         = "redis"
  engine_version = "7.1"
  node_type      = var.elasticache_node_type
  port           = 6379

  num_cache_clusters = var.elasticache_num_cache_clusters # prod: 2 (Primary+Replica) / dev: 1

  automatic_failover_enabled = var.elasticache_automatic_failover_enabled
  multi_az_enabled           = var.elasticache_multi_az_enabled

  subnet_group_name  = aws_elasticache_subnet_group.redis.name
  security_group_ids = [aws_security_group.elasticache.id]

  # 백업 불필요 — Athena 집계 결과가 별도로 RDS에 영구 저장됨
  snapshot_retention_limit = 0

  tags = {
    Name = "${var.cluster_name}-redis"
  }
}

resource "aws_elasticache_subnet_group" "redis" {
  name       = "${var.cluster_name}-redis-subnet-group"
  subnet_ids = var.database_subnets

  tags = {
    Name = "${var.cluster_name}-redis-subnet-group"
  }
}
