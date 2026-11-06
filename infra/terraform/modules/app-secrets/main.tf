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

# 소셜 로그인 OAuth 시크릿(snipy-cluster/app/oauth)은 prod를 지워도 남아야 해서 bootstrap이 소유한다 (#156).
# prod ExternalSecret이 이름으로 읽으므로 여기서는 만들지 않는다.

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