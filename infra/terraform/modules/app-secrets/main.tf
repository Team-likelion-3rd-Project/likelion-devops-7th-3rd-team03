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

# 소셜 로그인 OAuth 값. 외부에서 발급받은 값이라 TF 변수로 받는다
# (로컬: $env:TF_VAR_kakao_client_id 등, CI: GitHub production Environment secrets).
# Secrets Manager는 빈 문자열을 저장할 수 없어서 provider별 시크릿 대신 JSON 하나로 묶고,
# 값이 빈 provider는 키를 빼서 앱에서 비활성(로그인 요청 시 400)이 되게 한다.
# prod ExternalSecret(dataFrom.extract)이 JSON 키를 그대로 환경변수 이름으로 쓴다.
resource "aws_secretsmanager_secret" "oauth" {
  name                    = "${var.cluster_name}/app/oauth"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "oauth" {
  secret_id = aws_secretsmanager_secret.oauth.id
  secret_string = jsonencode({
    for k, v in {
      KAKAO_CLIENT_ID      = var.kakao_client_id
      KAKAO_CLIENT_SECRET  = var.kakao_client_secret
      GOOGLE_CLIENT_ID     = var.google_client_id
      GOOGLE_CLIENT_SECRET = var.google_client_secret
      NAVER_CLIENT_ID      = var.naver_client_id
      NAVER_CLIENT_SECRET  = var.naver_client_secret
    } : k => v if v != ""
  })
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