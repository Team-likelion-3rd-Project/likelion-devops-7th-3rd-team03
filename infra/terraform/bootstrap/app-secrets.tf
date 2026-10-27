# ══════════════════════════════════════════════════
# 소셜 로그인 OAuth 시크릿
# ══════════════════════════════════════════════════
# 카카오·구글·네이버 개발자 콘솔에서 발급받은 값이라 terraform이 만들 수 없다.
# prod를 지웠다 다시 만들어도 남아야 해서 state 버킷·ECR처럼 bootstrap이 소유한다.
# terraform은 이름만 만들고 값은 관리하지 않는다. 그래서 prod apply·CI에 OAuth 값이 필요 없고,
# 값이 terraform state에도 남지 않는다.
#
# 값은 계정당 한 번 콘솔 또는 CLI로 넣는다 (infra/terraform/README.md):
#   {"KAKAO_CLIENT_ID":"...","KAKAO_CLIENT_SECRET":"...","GOOGLE_CLIENT_ID":"...",
#    "GOOGLE_CLIENT_SECRET":"...","NAVER_CLIENT_ID":"...","NAVER_CLIENT_SECRET":"..."}
#   prod ExternalSecret(dataFrom.extract)이 키를 그대로 환경변수 이름으로 쓴다.
#   쓰지 않는 provider는 키를 빼면 그 로그인이 꺼진다 (Secrets Manager는 빈 문자열을 저장할 수 없다).
# 값을 넣기 전에 prod를 올리면 ExternalSecret 동기화가 실패해 management-service가 뜨지 않는다.
#
# 이름의 접두사는 prod ESO 권한 범위(<cluster_name>/*)에 맞춘다.
resource "aws_secretsmanager_secret" "oauth" {
  name                    = "${var.cluster_name}/app/oauth"
  description             = "소셜 로그인 OAuth 값 (JSON). 값은 terraform 밖에서 관리한다"
  recovery_window_in_days = 0
}