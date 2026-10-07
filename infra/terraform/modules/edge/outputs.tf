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
  description = "k8s/ingress-with-origin-verify.yaml의 alb.ingress.kubernetes.io/security-groups 값으로 채우세요"
  value       = aws_security_group.alb.id
}

output "cloudfront_domain_name" {
  description = "origin_alb_domain_name 채운 뒤 apply해야 값이 생김"
  value       = var.origin_alb_domain_name != "" ? aws_cloudfront_distribution.main[0].domain_name : "Ingress 배포 후 origin_alb_domain_name 채우고 재적용 필요"
}

output "alb_certificate_note" {
  description = "ap-southeast-1(ALB용) ACM 인증서는 별도로 이미 발급받으셨다고 하셨으니 그 ARN을 Ingress 매니페스트에 채워주세요"
  value       = "ALB(Ingress)용 ACM 인증서 ARN을 infra/k8s/ingress-with-origin-verify.yaml에 직접 입력하세요"
}
