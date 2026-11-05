# ══════════════════════════════════════════════════
# 온프렘 백업 버킷 (#221, #242)
# ══════════════════════════════════════════════════
# 온프렘 cp1이 만든 etcd 스냅샷과 MySQL 덤프(infra/onprem/backup)를 한 부 더 올리는 곳이다.
# 클러스터나 VM이 통째로 사라져도 백업이 남아야 해서, 배포일마다 destroy되는 prod가 아니라 bootstrap이 소유한다.
# etcd 스냅샷에는 클러스터의 모든 Secret(Sealed Secrets 개인키 포함)이 들어 있다. 그래서 버킷은 비공개, 암호화, TLS 전용으로 둔다.
#
# 경로: onprem/<클러스터 이름>/etcd/, onprem/<클러스터 이름>/mysql/
# 버저닝은 끈다. 파일 이름에 시각이 들어가서 덮어쓸 일이 없고, 보관은 아래 수명 주기가 맡는다.

locals {
  onprem_backup_bucket_name = "snipy-onprem-backup-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket" "onprem_backup" {
  bucket = local.onprem_backup_bucket_name

  tags = {
    Name = local.onprem_backup_bucket_name
  }

  # 버킷이 지워지면 오프사이트 백업이 모두 사라진다. terraform이 지우려 하면 plan에서 멈춘다
  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "onprem_backup" {
  bucket = aws_s3_bucket.onprem_backup.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "onprem_backup" {
  bucket = aws_s3_bucket.onprem_backup.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_policy" "onprem_backup_tls_only" {
  bucket = aws_s3_bucket.onprem_backup.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource = [
        aws_s3_bucket.onprem_backup.arn,
        "${aws_s3_bucket.onprem_backup.arn}/*",
      ]
      Condition = {
        Bool = { "aws:SecureTransport" = "false" }
      }
    }]
  })

  depends_on = [aws_s3_bucket_public_access_block.onprem_backup]
}

# 로컬(cp1)은 최근 7개를 보관하므로, 그보다 오래된 시점은 S3에서 복구한다
resource "aws_s3_bucket_lifecycle_configuration" "onprem_backup" {
  bucket = aws_s3_bucket.onprem_backup.id

  rule {
    id     = "expire-onprem-backups"
    status = "Enabled"

    filter {
      prefix = "onprem/"
    }

    expiration {
      days = var.onprem_backup_retention_days
    }

    # 업로드가 도중에 끊겨 남은 조각(multipart)은 목록에 안 보이지만 요금이 나간다
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

# ── cp1 업로드 전용 IAM 사용자 ──
# 온프렘은 OIDC로 역할을 받을 수 없어서 액세스 키를 쓴다. 키가 새도 피해가 작게 권한을 최소로 둔다.
#   자기 경로(onprem/<클러스터 이름>/*)에 올리기만 한다. 읽기(Get), 목록(List), 삭제(Delete)는 없다
#   그래서 cp1에서는 S3 백업을 내려받을 수 없다. 복구할 때는 팀 AWS 프로필이나 콘솔로 내려받는다
# 액세스 키는 terraform으로 만들지 않는다. 만들면 비밀 키가 state에 평문으로 남는다.
# apply 뒤에 IAM 권한이 있는 프로필로 발급해 ON 담당자에게 따로 전달한다 (infra/terraform/README.md 3-5).
resource "aws_iam_user" "onprem_backup" {
  name = "snipy-onprem-backup"
}

resource "aws_iam_user_policy" "onprem_backup_upload" {
  name = "snipy-onprem-backup-upload"
  user = aws_iam_user.onprem_backup.name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "UploadOwnClusterOnly"
      Effect = "Allow"
      # 큰 파일은 aws s3 cp가 나눠 올린다(multipart). 그 업로드도 PutObject 권한으로 된다.
      # AbortMultipartUpload는 실패한 업로드의 조각을 CLI가 스스로 정리하는 데 쓴다
      Action = [
        "s3:PutObject",
        "s3:AbortMultipartUpload",
      ]
      Resource = "${aws_s3_bucket.onprem_backup.arn}/onprem/${var.onprem_cluster_name}/*"
    }]
  })
}
