resource "random_password" "jwt_secret" {
  length  = 40
  special = false
}

resource "aws_secretsmanager_secret" "jwt_secret" {
  name                    = "${var.cluster_name}/app/jwt-secret"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "jwt_secret" {
  secret_id     = aws_secretsmanager_secret.jwt_secret.id
  secret_string = random_password.jwt_secret.result
}

# Kakao 값은 외부에서 발급받은 실제 값이라 terraform이 생성 못 함 —
# 빈 placeholder로 만들어두고, apply 후 aws cli로 실제 값 1회 입력 필요
resource "aws_secretsmanager_secret" "kakao_client_id" {
  name                    = "${var.cluster_name}/app/kakao-client-id"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret" "kakao_client_secret" {
  name                    = "${var.cluster_name}/app/kakao-client-secret"
  recovery_window_in_days = 0
}

resource "random_password" "grafana_admin" {
  length  = 24
  special = false
}

resource "aws_secretsmanager_secret" "grafana_admin_password" {
  name                    = "${var.cluster_name}/app/grafana-admin-password"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "grafana_admin_password" {
  secret_id     = aws_secretsmanager_secret.grafana_admin_password.id
  secret_string = random_password.grafana_admin.result
}