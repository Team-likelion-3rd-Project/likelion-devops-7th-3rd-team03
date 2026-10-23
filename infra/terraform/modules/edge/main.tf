# ══════════════════════════════════════════════════
# Route53 Hosted Zone 조회 (이미 등록된 도메인)
# ══════════════════════════════════════════════════
data "aws_route53_zone" "main" {
  name = var.route53_zone_name
}

# ══════════════════════════════════════════════════
# ACM 인증서 — CloudFront용 (반드시 us-east-1)
# ══════════════════════════════════════════════════
resource "aws_acm_certificate" "cloudfront" {
  provider          = aws.us_east_1
  domain_name       = var.domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Name = "${var.cluster_name}-cloudfront-cert"
  }
}

resource "aws_route53_record" "cloudfront_cert_validation" {
  for_each = {
    for dvo in aws_acm_certificate.cloudfront.domain_validation_options : dvo.domain_name => {
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

resource "aws_acm_certificate_validation" "cloudfront" {
  provider                = aws.us_east_1
  certificate_arn         = aws_acm_certificate.cloudfront.arn
  validation_record_fqdns = [for r in aws_route53_record.cloudfront_cert_validation : r.fqdn]
}

# ══════════════════════════════════════════════════
# ACM 인증서 — ALB용 (서비스 리전, 기본 프로바이더)
# ══════════════════════════════════════════════════
# CloudFront가 Host 헤더(도메인)를 그대로 넘기므로 ALB와의 TLS도 그 이름으로 맺는다.
# Ingress에 ARN을 적지 않아도 AWS Load Balancer Controller가 Ingress의 host와
# 맞는 인증서를 찾아 붙인다. alb.<도메인>은 직접 점검용.
resource "aws_acm_certificate" "alb" {
  domain_name               = var.domain_name
  subject_alternative_names = ["alb.${var.domain_name}"]
  validation_method         = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Name = "${var.cluster_name}-alb-cert"
  }
}

# 도메인 검증 레코드는 CloudFront용 인증서와 같은 이름이라 겹칠 수 있어 allow_overwrite
resource "aws_route53_record" "alb_cert_validation" {
  for_each = {
    for dvo in aws_acm_certificate.alb.domain_validation_options : dvo.domain_name => {
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

resource "aws_acm_certificate_validation" "alb" {
  certificate_arn         = aws_acm_certificate.alb.arn
  validation_record_fqdns = [for r in aws_route53_record.alb_cert_validation : r.fqdn]
}

# ══════════════════════════════════════════════════
# X-Origin-Verify 시크릿 — CloudFront만 이 값을 헤더에 담아 ALB로 보냄
# ALB(Ingress)는 이 값이 없거나 다르면 요청을 거부 → ALB 직접 접근 차단
# ══════════════════════════════════════════════════
resource "random_password" "origin_verify" {
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "origin_verify" {
  name                    = "${var.cluster_name}/cloudfront/origin-verify-secret"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "origin_verify" {
  secret_id     = aws_secretsmanager_secret.origin_verify.id
  secret_string = random_password.origin_verify.result
}

# ══════════════════════════════════════════════════
# WAF Web ACL (CloudFront scope — 반드시 us-east-1)
# ══════════════════════════════════════════════════
resource "aws_wafv2_web_acl" "cloudfront" {
  provider = aws.us_east_1
  name     = "${var.cluster_name}-waf"
  scope    = "CLOUDFRONT"

  default_action {
    allow {}
  }

  # Rate-based Rule: 5분당 동일 IP 2000req 초과 시 일시 차단
  rule {
    name     = "rate-limit"
    priority = 1

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit              = 2000
        aggregate_key_type = "IP"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.cluster_name}-rate-limit"
      sampled_requests_enabled   = true
    }
  }

  # AWS Managed Rules — Core Rule Set (OWASP Top10 대응)
  rule {
    name     = "aws-managed-core-rule-set"
    priority = 2

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesCommonRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.cluster_name}-core-rule-set"
      sampled_requests_enabled   = true
    }
  }

  # AWS Managed Rules — Known Bad Inputs
  rule {
    name     = "aws-managed-known-bad-inputs"
    priority = 3

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.cluster_name}-known-bad-inputs"
      sampled_requests_enabled   = true
    }
  }

  # (선택) Geo-Match Rule — 서비스 대상국 외 접근 제한
  dynamic "rule" {
    for_each = var.waf_geo_match_enabled ? [1] : []
    content {
      name     = "geo-match-restriction"
      priority = 4

      action {
        block {}
      }

      statement {
        not_statement {
          statement {
            geo_match_statement {
              country_codes = var.waf_allowed_countries
            }
          }
        }
      }

      visibility_config {
        cloudwatch_metrics_enabled = true
        metric_name                = "${var.cluster_name}-geo-match"
        sampled_requests_enabled   = true
      }
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${var.cluster_name}-waf"
    sampled_requests_enabled   = true
  }

  lifecycle {
    ignore_changes = [rule]
  }

  tags = {
    Name = "${var.cluster_name}-waf"
  }
}

# ══════════════════════════════════════════════════
# CloudFront Distribution
# ══════════════════════════════════════════════════
# 오리진(ALB)은 AWS Load Balancer Controller(k8s Ingress)가 나중에 만든다.
# 그래서 ALB 주소 대신 고정 이름 alb.<도메인>을 오리진으로 두고, 그 레코드는
# external-dns가 Ingress를 보고 만든다(modules/cluster). ALB가 생기기 전에도
# 배포는 만들어지고, 레코드가 생기면 그때부터 요청이 전달된다.
resource "aws_cloudfront_distribution" "main" {
  enabled     = true
  aliases     = [var.domain_name]
  price_class = "PriceClass_200" # 아시아 포함 리전, 비용 절감형

  origin {
    domain_name = "alb.${var.domain_name}"
    origin_id   = "alb-origin"

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }

    # ALB(Ingress)가 이 헤더를 검증해서 CloudFront를 거치지 않은 직접 접근을 차단
    custom_header {
      name  = "X-Origin-Verify"
      value = random_password.origin_verify.result
    }
  }

  default_cache_behavior {
    allowed_methods        = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods         = ["GET", "HEAD"]
    target_origin_id       = "alb-origin"
    viewer_protocol_policy = "redirect-to-https"

    forwarded_values {
      query_string = true
      headers      = ["Authorization", "Origin", "Referer", "Host", "User-Agent", "Accept-Language"]
      cookies {
        forward = "all"
      }
    }

    # API/동적 요청 위주라 캐싱은 최소화 (링크 리다이렉트, API 응답 등 캐시하면 안 됨)
    min_ttl     = 0
    default_ttl = 0
    max_ttl     = 0
  }

  restrictions {
    geo_restriction {
      restriction_type = "none" # 국가 제한은 WAF Geo-Match Rule에서 처리
    }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.cloudfront.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }

  web_acl_id = aws_wafv2_web_acl.cloudfront.arn

  tags = {
    Name = "${var.cluster_name}-cloudfront"
  }
}

# ══════════════════════════════════════════════════
# Route53 — 도메인 → CloudFront Alias
# ══════════════════════════════════════════════════
resource "aws_route53_record" "cloudfront_alias" {
  zone_id = data.aws_route53_zone.main.zone_id
  name    = var.domain_name
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.main.domain_name
    zone_id                = aws_cloudfront_distribution.main.hosted_zone_id
    evaluate_target_health = false
  }
}

# ══════════════════════════════════════════════════
# ALB 보안그룹 — CloudFront 관리형 프리픽스 리스트에서 오는 트래픽만 허용
# ══════════════════════════════════════════════════
# X-Origin-Verify 헤더 검증(애플리케이션 레벨)만으로는 ALB 직접 접근을 막지 못하므로,
# 네트워크 레벨에서도 CloudFront origin-facing IP 대역 외의 인바운드를 차단.
# Ingress는 이 SG를 ID가 아니라 이름(Name 태그)으로 지정한다
# (infra/k8s/components/aws/ingress-with-origin-verify.yaml의 security-groups).
data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

resource "aws_security_group" "alb" {
  name        = "${var.cluster_name}-alb-sg"
  description = "Allow HTTPS only from CloudFront managed prefix list"
  vpc_id      = var.vpc_id

  ingress {
    description     = "HTTPS from CloudFront"
    from_port       = 443
    to_port         = 443
    protocol        = "tcp"
    prefix_list_ids = [data.aws_ec2_managed_prefix_list.cloudfront.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.cluster_name}-alb-sg"
  }
}

resource "aws_security_group_rule" "alb_to_nodes" {
  type                     = "ingress"
  from_port                = 8080
  to_port                  = 8080
  protocol                 = "tcp"
  security_group_id        = var.node_security_group_id
  source_security_group_id = aws_security_group.alb.id
  description              = "ALB to pods (target-type ip) on container port 8080"
}