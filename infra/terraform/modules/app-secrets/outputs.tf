output "jwt_secret_arn" {
  value = aws_secretsmanager_secret.jwt_secret.arn
}

output "kakao_client_id_arn" {
  value = aws_secretsmanager_secret.kakao_client_id.arn
}

output "kakao_client_secret_arn" {
  value = aws_secretsmanager_secret.kakao_client_secret.arn
}

output "grafana_admin_password_arn" {
  value = aws_secretsmanager_secret.grafana_admin_password.arn
}