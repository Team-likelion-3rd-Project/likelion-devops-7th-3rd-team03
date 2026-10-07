# ══════════════════════════════════════════════════
# 프론트엔드 정적 호스팅 — S3 + CloudFront(OAC) + ACM + Route53
# snipy.life(ALB, modules/edge)와는 별도 배포.
# ALB 리스너 규칙상 API(snipy.life/api/*)와 리다이렉트(snipy.life/*)만 처리하므로,
# 정적 프론트는 호스트 분리해서 붙인다 (CloudFront는 오리진을 호스트 기준으로 못 나눔).
# ══════════════════════════════════════════════════

data "aws_route53_zone" "main" {
  name = var.route53_zone_name
}

# ══════════════════════════════════════════════════
# S3 — 프론트 빌드 산출물
# ══════════════════════════════════════════════════
resource "aws_s3_bucket" "frontend" {
  bucket = "${var.cluster_name}-frontend-team03"

  tags = {
    Name = "${var.cluster_name}-frontend"
  }
}

resource "aws_s3_bucket_public_access_block" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = true
  restrict_public_buckets = true
}

# ══════════════════════════════════════════════════
# CloudFront Origin Access Control — S3는 이 CloudFront 배포에서만 접근 허용
# ══════════════════════════════════════════════════
resource "aws_cloudfront_origin_access_control" "frontend" {
  name                              = "${var.cluster_name}-frontend-oac"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

data "aws_iam_policy_document" "frontend_bucket_policy" {
  statement {
    sid       = "AllowCloudFrontServicePrincipal"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.frontend.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.frontend.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "frontend" {
  bucket = aws_s3_bucket.frontend.id
  policy = data.aws_iam_policy_document.frontend_bucket_policy.json
}

# ══════════════════════════════════════════════════
# ACM 인증서 — 프론트 도메인 전용 (반드시 us-east-1)
# snipy.life 인증서(modules/edge)는 이미 다른 배포(ALB용)에서 쓰는 중이라 재사용 불가
# ══════════════════════════════════════════════════
resource "aws_acm_certificate" "frontend" {
  provider          = aws.us_east_1
  domain_name       = var.domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Name = "${var.cluster_name}-frontend-cert"
  }
}

resource "aws_route53_record" "frontend_cert_validation" {
  for_each = {
    for dvo in aws_acm_certificate.frontend.domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      record = dvo.resource_record_value
      type   = dvo.resource_record_type
    }
  }

  allow_overwrite = true
  name            = each.value.name
  records         = [each.value.record]
  ttl             = 60
  type            = each.value.type
  zone_id         = data.aws_route53_zone.main.zone_id
}

resource "aws_acm_certificate_validation" "frontend" {
  provider                = aws.us_east_1
  certificate_arn         = aws_acm_certificate.frontend.arn
  validation_record_fqdns = [for r in aws_route53_record.frontend_cert_validation : r.fqdn]
}

# ══════════════════════════════════════════════════
# CloudFront Distribution — S3 정적 호스팅, SPA 라우팅 지원
# ══════════════════════════════════════════════════
data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

resource "aws_cloudfront_distribution" "frontend" {
  enabled             = true
  aliases             = [var.domain_name]
  default_root_object = "index.html"
  price_class         = "PriceClass_200"

  origin {
    domain_name              = aws_s3_bucket.frontend.bucket_regional_domain_name
    origin_id                = "s3-frontend-origin"
    origin_access_control_id = aws_cloudfront_origin_access_control.frontend.id
  }

  default_cache_behavior {
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    target_origin_id       = "s3-frontend-origin"
    viewer_protocol_policy = "redirect-to-https"
    # Managed-CachingOptimized의 기본 TTL은 1일이지만, 오리진(S3)이 명시적
    # Cache-Control을 주면 그걸 우선한다. index.html은 배포 시
    # `aws s3 cp ... --cache-control "no-cache, no-store, must-revalidate"`로
    # 해시 안 붙은 진입점 파일이라 즉시 갱신되어야 하기 때문 — 그래서 실제로는
    # 1일 캐시되지 않는다. 이 짧은 TTL은 이 리소스가 아니라 배포 스크립트가
    # 보장한다 (여기서 강제하지 않음 — 배포 커맨드를 빠뜨리면 깨질 수 있음).
    cache_policy_id = data.aws_cloudfront_cache_policy.caching_optimized.id
    compress        = true
  }

  # SPA(React Router)라 존재하지 않는 경로는 index.html로 돌려보내 클라이언트 라우팅에 맡긴다.
  # S3는 OAC로 막혀있어 실제로는 403이 나오므로 403도 같이 처리.
  custom_error_response {
    error_code            = 403
    response_code         = 200
    response_page_path    = "/index.html"
    error_caching_min_ttl = 10
  }

  custom_error_response {
    error_code            = 404
    response_code         = 200
    response_page_path    = "/index.html"
    error_caching_min_ttl = 10
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.frontend.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }

  tags = {
    Name = "${var.cluster_name}-frontend-cloudfront"
  }
}

# ══════════════════════════════════════════════════
# Route53 — 프론트 도메인 → CloudFront Alias
# ══════════════════════════════════════════════════
resource "aws_route53_record" "frontend_alias" {
  zone_id = data.aws_route53_zone.main.zone_id
  name    = var.domain_name
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.frontend.domain_name
    zone_id                = aws_cloudfront_distribution.frontend.hosted_zone_id
    evaluate_target_health = false
  }
}
