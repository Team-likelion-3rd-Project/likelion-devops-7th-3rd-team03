# ══════════════════════════════════════════════════
# terraform CI(.github/workflows/terraform.yml)용 OIDC 역할
# ══════════════════════════════════════════════════
# env/prod terraform을 사람 PC 대신 GitHub Actions가 실행한다 (#157).
# 역할을 둘로 나눠, PR을 올린 사람이 인프라를 바꾸는 권한을 받지 못하게 한다.
#   plan  : PR에서 plan만 한다. 읽기 전용 (-refresh=false -lock=false라 state 쓰기·클러스터 접근 불필요)
#   apply : GitHub Environment "production"을 거친 job만 받는다 (승인자 승인 + 허용 브랜치)
# 이 역할들은 이 bootstrap이 만들므로 bootstrap 자체는 계정당 한 번 로컬에서 apply한다.

locals {
  # sub claim의 저장소 부분. 조직·저장소 이름 뒤에 고유 ID가 붙는 형식이다 (snipy-github-actions-ci와 같음)
  github_repo_sub = "repo:Team-likelion-3rd-Project@336729042/likelion-devops-7th-3rd-team03@1400844527"
}

resource "aws_iam_role" "terraform_plan" {
  name = "snipy-terraform-plan"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.github_actions.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          "token.actions.githubusercontent.com:sub" = "${local.github_repo_sub}:pull_request"
        }
      }
    }]
  })
}

# 읽기 전용. state 파일도 읽을 수 있으므로(RDS 비밀번호 등 포함) 저장소에 push 권한이 있는
# 팀원은 PR 워크플로를 고쳐 state를 볼 수 있다. 팀원을 신뢰하는 전제로 PR plan을 쓴다.
resource "aws_iam_role_policy_attachment" "terraform_plan_readonly" {
  role       = aws_iam_role.terraform_plan.name
  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

resource "aws_iam_role" "terraform_apply" {
  name = "snipy-terraform-apply"

  # apply는 30~40분 걸린다. 기본 세션 1시간을 넘기면 중간에 자격증명이 만료된다
  max_session_duration = 7200

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.github_actions.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          # Environment를 쓰는 job만 이 sub를 받는다. 승인자·허용 브랜치는 GitHub Environment 설정이 막는다
          "token.actions.githubusercontent.com:sub" = "${local.github_repo_sub}:environment:production"
        }
      }
    }]
  })
}

# VPC·EKS·RDS·IAM 역할 등을 만들어야 해서 관리자 권한이 필요하다. 대신 받을 수 있는 경로를 위 신뢰 조건으로 좁힌다.
resource "aws_iam_role_policy_attachment" "terraform_apply_admin" {
  role       = aws_iam_role.terraform_apply.name
  policy_arn = "arn:aws:iam::aws:policy/AdministratorAccess"
}

# GitHub Variables TF_PLAN_ROLE_ARN·TF_APPLY_ROLE_ARN에 등록한다
output "terraform_plan_role_arn" {
  value = aws_iam_role.terraform_plan.arn
}

output "terraform_apply_role_arn" {
  value = aws_iam_role.terraform_apply.arn
}
