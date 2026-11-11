output "route53_zone_id" {
  value = data.aws_route53_zone.main.zone_id
}

output "cloudfront_acm_certificate_arn" {
  description = "us-east-1에 발급된 CloudFront 전용 ACM 인증서"
  value       = aws_acm_certificate_validation.cloudfront.certificate_arn
}

output "waf_web_acl_arn" {
  value = aws_wafv2_web_acl.cloudfront.arn
}

output "origin_verify_secret_arn" {
  description = "X-Origin-Verify 헤더 값 — K8s Secret으로 주입해서 애플리케이션 필터가 검증"
  value       = aws_secretsmanager_secret.origin_verify.arn
}

output "alb_security_group_id" {
  description = "Ingress는 이 SG를 이름(<cluster_name>-alb-sg)으로 지정한다"
  value       = aws_security_group.alb.id
}

output "alb_acm_certificate_arn" {
  description = "ALB용 ACM 인증서. Ingress host와 맞으면 AWS Load Balancer Controller가 자동으로 붙인다"
  value       = aws_acm_certificate_validation.alb.certificate_arn
}

output "cloudfront_domain_name" {
  value = aws_cloudfront_distribution.main.domain_name
}
