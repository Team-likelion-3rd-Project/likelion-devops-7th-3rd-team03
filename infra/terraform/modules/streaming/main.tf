# ══════════════════════════════════════════════════
# S3 — 원시 클릭 로그 저장 (날짜별 파티셔닝)
# ══════════════════════════════════════════════════
resource "aws_s3_bucket" "click_logs" {
  bucket        = "${var.cluster_name}-click-logs-${data.aws_caller_identity.current.account_id}"
  force_destroy = true # 테스트로 켰다 끄는 프로젝트라, 데이터 남아있어도 destroy 가능하게

  tags = {
    Name = "${var.cluster_name}-click-logs"
  }
}

# 학습용 프로젝트, 짧은 기간만 운영 — 오래된 로그는 자동 정리해서 스토리지 비용 방지
resource "aws_s3_bucket_lifecycle_configuration" "click_logs" {
  bucket = aws_s3_bucket.click_logs.id

  rule {
    id     = "expire-old-logs"
    status = "Enabled"

    filter {}

    expiration {
      days = 14
    }
  }
}

resource "aws_s3_bucket_public_access_block" "click_logs" {
  bucket = aws_s3_bucket.click_logs.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Athena 쿼리 결과 저장용 (원시 로그와 분리)
resource "aws_s3_bucket" "athena_results" {
  bucket        = "${var.cluster_name}-athena-results-${data.aws_caller_identity.current.account_id}"
  force_destroy = true

  tags = {
    Name = "${var.cluster_name}-athena-results"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "athena_results" {
  bucket = aws_s3_bucket.athena_results.id

  rule {
    id     = "expire-query-results"
    status = "Enabled"

    filter {}

    expiration {
      days = 7
    }
  }
}

data "aws_caller_identity" "current" {}

# ══════════════════════════════════════════════════
# Firehose — Direct PUT → S3, 날짜별 파티셔닝
# ══════════════════════════════════════════════════
# Redis 실시간 통계(#72)가 이미 별도 직행 경로로 처리되고 있어,
# 클릭 이벤트를 S3에 적재하는 유일한 목적을 위해 Kinesis Data Stream을
# 중간에 둘 이유가 없다. redirect-service가 Firehose에 직접 PutRecord한다.
resource "aws_iam_role" "firehose" {
  name = "${var.cluster_name}-firehose-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "firehose.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "firehose" {
  name = "${var.cluster_name}-firehose-policy"
  role = aws_iam_role.firehose.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "s3:AbortMultipartUpload",
          "s3:GetBucketLocation",
          "s3:GetObject",
          "s3:ListBucket",
          "s3:ListBucketMultipartUploads",
          "s3:PutObject"
        ]
        Resource = [
          aws_s3_bucket.click_logs.arn,
          "${aws_s3_bucket.click_logs.arn}/*"
        ]
      }
    ]
  })
}

resource "aws_kinesis_firehose_delivery_stream" "click_events" {
  name        = "${var.cluster_name}-click-events-firehose"
  destination = "extended_s3"

  # kinesis_source_configuration 블록 없음 = Direct PUT 모드.
  # redirect-service가 이 스트림에 PutRecord/PutRecordBatch로 직접 씀.

  extended_s3_configuration {
    role_arn   = aws_iam_role.firehose.arn
    bucket_arn = aws_s3_bucket.click_logs.arn

    # 날짜별 파티셔닝 — Athena가 하루 1번 배치에서 오늘 파티션만 스캔하도록
    prefix              = "year=!{timestamp:yyyy}/month=!{timestamp:MM}/day=!{timestamp:dd}/"
    error_output_prefix = "errors/!{firehose:error-output-type}/year=!{timestamp:yyyy}/month=!{timestamp:MM}/day=!{timestamp:dd}/"

    buffering_size     = 5   # MB — 트래픽 적으니 크기보다 시간 기준으로 flush됨
    buffering_interval = 300 # 5분마다 flush (실시간성보다 비용 효율 우선)

    compression_format = "GZIP"
  }

  tags = {
    Name = "${var.cluster_name}-click-events-firehose"
  }
}

# ══════════════════════════════════════════════════
# Glue Data Catalog — Athena가 S3의 클릭 로그를 테이블로 인식하게 함
# ══════════════════════════════════════════════════
resource "aws_glue_catalog_database" "snipy" {
  name = var.glue_database_name != "" ? var.glue_database_name : "${var.cluster_name}_click_logs"
}

resource "aws_glue_catalog_table" "click_events" {
  name          = "click_events"
  database_name = aws_glue_catalog_database.snipy.name
  table_type    = "EXTERNAL_TABLE"

  parameters = {
    "classification"            = "json"
    "projection.enabled"        = "true"
    "projection.year.type"      = "integer"
    "projection.year.range"     = "2026,2030"
    "projection.month.type"     = "integer"
    "projection.month.range"    = "1,12"
    "projection.month.digits"   = "2"
    "projection.day.type"       = "integer"
    "projection.day.range"      = "1,31"
    "projection.day.digits"     = "2"
    "storage.location.template" = "s3://${aws_s3_bucket.click_logs.bucket}/year=$${year}/month=$${month}/day=$${day}/"
  }

  storage_descriptor {
    location      = "s3://${aws_s3_bucket.click_logs.bucket}/"
    input_format  = "org.apache.hadoop.mapred.TextInputFormat"
    output_format = "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat"

    ser_de_info {
      serialization_library = "org.openx.data.jsonserde.JsonSerDe"
    }

    # redirect-service ClickEvent의 JSON(snake_case) 계약과 일치시킨다.
    columns {
      name = "event_id"
      type = "string"
    }
    columns {
      name = "schema_version"
      type = "int"
    }
    columns {
      name = "link_id"
      type = "bigint"
    }
    columns {
      name = "clicked_at"
      type = "string"
    }
    columns {
      name = "visitor_id"
      type = "string"
    }
    columns {
      name = "language"
      type = "string"
    }
    columns {
      name = "user_agent"
      type = "string"
    }
    columns {
      name = "device_type"
      type = "string"
    }
    columns {
      name = "operating_system"
      type = "string"
    }
    columns {
      name = "browser"
      type = "string"
    }
    columns {
      name = "referrer"
      type = "string"
    }
    columns {
      name = "referrer_category"
      type = "string"
    }
    columns {
      name = "is_bot"
      type = "boolean"
    }
  }

  # 날짜별 파티션 (Firehose의 S3 prefix 구조와 일치)
  partition_keys {
    name = "year"
    type = "string"
  }
  partition_keys {
    name = "month"
    type = "string"
  }
  partition_keys {
    name = "day"
    type = "string"
  }
}

# ══════════════════════════════════════════════════
# Athena Workgroup — 쿼리 결과 위치 + 비용 통제
# ══════════════════════════════════════════════════
resource "aws_athena_workgroup" "snipy" {
  name = "${var.cluster_name}-workgroup"

  configuration {
    enforce_workgroup_configuration    = true
    publish_cloudwatch_metrics_enabled = true

    result_configuration {
      output_location = "s3://${aws_s3_bucket.athena_results.bucket}/"
    }

    # 트래픽이 거의 없는 프로젝트라 스캔량 자체가 작겠지만,
    # 안전장치로 쿼리 1회당 스캔 상한을 걸어둠 (풀스캔하는 것 방지)
    bytes_scanned_cutoff_per_query = 1073741824 # 1GB
  }
}

# ══════════════════════════════════════════════════
# CronJob(IRSA)이 Athena/Glue/S3에 접근할 IAM Role
# ══════════════════════════════════════════════════
resource "aws_iam_role" "athena_cronjob" {
  name = "${var.cluster_name}-athena-cronjob-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Federated = var.oidc_provider_arn
      }
      Action = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${replace(var.oidc_provider, "https://", "")}:sub" = "system:serviceaccount:default:athena-batch-sa"
          "${replace(var.oidc_provider, "https://", "")}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "athena_cronjob" {
  name = "${var.cluster_name}-athena-cronjob-policy"
  role = aws_iam_role.athena_cronjob.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "athena:StartQueryExecution",
          "athena:GetQueryExecution",
          "athena:GetQueryResults",
          "athena:StopQueryExecution"
        ]
        Resource = aws_athena_workgroup.snipy.arn
      },
      {
        Effect = "Allow"
        Action = [
          "glue:GetTable",
          "glue:GetDatabase",
          "glue:GetPartitions"
        ]
        Resource = "*"
      },
      {
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:ListBucket"
        ]
        Resource = [
          aws_s3_bucket.click_logs.arn,
          "${aws_s3_bucket.click_logs.arn}/*"
        ]
      },
      {
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:GetBucketLocation",
          "s3:ListBucket"
        ]
        Resource = [
          aws_s3_bucket.athena_results.arn,
          "${aws_s3_bucket.athena_results.arn}/*"
        ]
      }
    ]
  })
}

# ══════════════════════════════════════════════════
# redirect-service(IRSA)가 Firehose에 클릭 이벤트를 넣을 IAM Role
# ══════════════════════════════════════════════════
resource "aws_iam_role" "redirect_service" {
  name = "${var.cluster_name}-redirect-service-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Federated = var.oidc_provider_arn
      }
      Action = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${replace(var.oidc_provider, "https://", "")}:sub" = "system:serviceaccount:default:redirect-service-sa"
          "${replace(var.oidc_provider, "https://", "")}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "redirect_service" {
  name = "${var.cluster_name}-redirect-service-policy"
  role = aws_iam_role.redirect_service.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "firehose:PutRecord",
        "firehose:PutRecordBatch"
      ]
      Resource = aws_kinesis_firehose_delivery_stream.click_events.arn
    }]
  })
}
