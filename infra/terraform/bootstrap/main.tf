# ══════════════════════════════════════════════════
# Terraform state 저장용 S3 버킷 (bootstrap)
# ══════════════════════════════════════════════════
# env/dev, env/prod가 S3 backend로 이 버킷을 쓴다. 그 backend를 쓰려면
# 버킷이 먼저 있어야 하는 닭-달걀 문제라, 이 모듈만 별도로 로컬 state로
# 관리한다 (backend 블록 없음 — providers.tf 참고).
#
# 적용 순서: bootstrap을 가장 먼저 apply해야 env/prod, env/dev의
# `terraform init`이 성공한다.
resource "aws_s3_bucket" "tfstate" {
  bucket = var.state_bucket_name

  tags = {
    Name = var.state_bucket_name
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

# ══════════════════════════════════════════════════
# ECR — dev/prod 공용
# ══════════════════════════════════════════════════
# env/prod, env/dev 둘 다에서 module "registry"를 호출하면 이름이
# cluster_name으로 구분되지 않는 리터럴이라 충돌한다. 이미지 저장소는
# dev/prod 코드가 같아 나눌 이유도 없다. 게다가 prod를 껐다 켰다 하는
# 운영이라(테스트 단계), prod state에 ECR을 묶어두면 prod를 destroy할
# 때 dev가 참조하던 이미지까지 같이 사라진다 — state 버킷처럼 "환경을
# 껐다 켜도 안 사라져야 하는 것"이라 bootstrap이 소유한다.
module "registry" {
  source = "../modules/registry"
}
