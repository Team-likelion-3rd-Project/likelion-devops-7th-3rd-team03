output "jwt_secret_arn" {
  value = aws_secretsmanager_secret.jwt_secret.arn
}

output "grafana_admin_password_arn" {
  value = aws_secretsmanager_secret.grafana_admin_password.arn
}