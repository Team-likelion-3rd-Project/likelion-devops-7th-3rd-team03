# ══════════════════════════════════════════════════
# Terraform state 저장용 S3 버킷 (bootstrap)
# ══════════════════════════════════════════════════
# env/prod가 S3 backend로 이 버킷을 쓴다. 그 backend를 쓰려면
# 버킷이 먼저 있어야 하는 닭-달걀 문제라, 이 모듈만 별도로 로컬 state로
# 관리한다 (backend 블록 없음 — providers.tf 참고).
#
# 적용 순서: bootstrap을 가장 먼저 apply해야 env/prod의 `terraform init`이 성공한다.
#
# 버킷 이름은 계정 ID로 정해진다 (S3 버킷 이름은 전 세계에서 유일해야 함).
# env/prod는 backend 블록에 버킷 이름을 두지 않으므로 init 때 넘긴다:
#   terraform init -backend-config="bucket=<output state_bucket_name>"
data "aws_caller_identity" "current" {}

locals {
  state_bucket_name = "snipy-terraform-state-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket" "tfstate" {
  bucket = local.state_bucket_name

  tags = {
    Name = local.state_bucket_name
  }
}

# state 손상이나 실수로 인한 덮어쓰기/삭제 시 이전 버전으로 복구할 수 있도록
resource "aws_s3_bucket_versioning" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  versioning_configuration {
    status = "Enabled"
  }
}

# env/*/providers.tf의 backend.encrypt = true와 짝을 맞춤
resource "aws_s3_bucket_server_side_encryption_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# state 파일엔 DB 비밀번호 등 민감정보가 평문으로 들어있으므로 퍼블릭 노출을 원천 차단
resource "aws_s3_bucket_public_access_block" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# 암호화되지 않은 연결(HTTP)로 state를 읽고 쓰지 못하게 막는다
resource "aws_s3_bucket_policy" "tfstate_tls_only" {
  bucket = aws_s3_bucket.tfstate.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource = [
        aws_s3_bucket.tfstate.arn,
        "${aws_s3_bucket.tfstate.arn}/*",
      ]
      Condition = {
        Bool = { "aws:SecureTransport" = "false" }
      }
    }]
  })

  depends_on = [aws_s3_bucket_public_access_block.tfstate]
}

# ══════════════════════════════════════════════════
# ECR
# ══════════════════════════════════════════════════
# prod를 껐다 켰다 하는 운영이라(테스트 단계), prod state에 ECR을 묶어두면
# prod를 destroy할 때 이미지까지 같이 사라진다 — state 버킷처럼 "환경을
# 껐다 켜도 안 사라져야 하는 것"이라 bootstrap이 소유한다.
module "registry" {
  source = "../modules/registry"
}
