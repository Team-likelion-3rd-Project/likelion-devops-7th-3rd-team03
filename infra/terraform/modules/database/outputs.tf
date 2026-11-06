output "rds_endpoint" {
  value = aws_db_instance.main.endpoint
}

output "rds_db_name" {
  value = aws_db_instance.main.db_name
}

output "rds_password_secret_arn" {
  description = "Secrets Manager에서 실제 비밀번호 조회"
  value       = aws_secretsmanager_secret.rds_password.arn
}

output "rds_app_password_secret_arn" {
  value = aws_secretsmanager_secret.rds_app_password.arn
}

output "rds_security_group_id" {
  value = aws_security_group.rds.id
}

output "elasticache_primary_endpoint" {
  value = aws_elasticache_replication_group.redis.primary_endpoint_address
}

output "elasticache_reader_endpoint" {
  value = aws_elasticache_replication_group.redis.reader_endpoint_address
}

output "elasticache_security_group_id" {
  value = aws_security_group.elasticache.id
}
