output "s3_bucket_name" {
  value = aws_s3_bucket.frontend.bucket
}

output "cloudfront_distribution_id" {
  value = aws_cloudfront_distribution.frontend.id
}

output "cloudfront_domain_name" {
  value = aws_cloudfront_distribution.frontend.domain_name
}

output "acm_certificate_arn" {
  description = "us-east-1에 발급된 프론트엔드 전용 ACM 인증서"
  value       = aws_acm_certificate_validation.frontend.certificate_arn
}

output "frontend_url" {
  value = "https://${var.domain_name}"
}
