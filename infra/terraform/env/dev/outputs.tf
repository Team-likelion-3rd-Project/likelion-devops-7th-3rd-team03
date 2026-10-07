output "cluster_endpoint" {
  description = "EKS cluster API endpoint"
  value       = module.cluster.cluster_endpoint
}

output "cluster_name" {
  value = module.cluster.cluster_name
}

output "configure_kubectl" {
  description = "kubectl 연결 명령어"
  value       = "aws eks update-kubeconfig --region ${var.aws_region} --name ${module.cluster.cluster_name}"
}

output "vpc_id" {
  value = module.network.vpc_id
}

output "private_subnets" {
  value = module.network.private_subnets
}

output "database_subnets" {
  value = module.network.database_subnets
}

output "database_subnet_group_name" {
  description = "RDS 생성 시 이 subnet group 이름을 사용"
  value       = module.network.database_subnet_group_name
}

output "management_service_ecr_url" {
  description = "bootstrap이 소유한 ECR (prod와 공유)"
  value       = data.aws_ecr_repository.management_service.repository_url
}

output "redirect_service_ecr_url" {
  description = "bootstrap이 소유한 ECR (prod와 공유)"
  value       = data.aws_ecr_repository.redirect_service.repository_url
}

output "elasticache_primary_endpoint" {
  value = module.database.elasticache_primary_endpoint
}

output "elasticache_reader_endpoint" {
  value = module.database.elasticache_reader_endpoint
}

output "rds_endpoint" {
  value = module.database.rds_endpoint
}

output "rds_db_name" {
  value = module.database.rds_db_name
}

output "rds_password_secret_arn" {
  description = "Secrets Manager에서 실제 비밀번호 조회"
  value       = module.database.rds_password_secret_arn
}

output "firehose_delivery_stream_name" {
  value = module.streaming.firehose_delivery_stream_name
}

output "click_logs_bucket" {
  value = module.streaming.click_logs_bucket
}

output "athena_workgroup" {
  value = module.streaming.athena_workgroup
}

output "glue_database_name" {
  value = module.streaming.glue_database_name
}

output "redirect_service_irsa_role_arn" {
  description = "redirect-service의 ServiceAccount에 이 ARN을 annotation으로 연결"
  value       = module.streaming.redirect_service_irsa_role_arn
}

output "athena_cronjob_irsa_role_arn" {
  description = "Athena 배치 CronJob의 ServiceAccount에 이 ARN을 annotation으로 연결"
  value       = module.streaming.athena_cronjob_irsa_role_arn
}

output "route53_zone_id" {
  value = module.edge.route53_zone_id
}

output "alb_certificate_note" {
  description = "ap-southeast-1(ALB용) ACM 인증서는 별도로 이미 발급받으셨다고 하셨으니 그 ARN을 Ingress 매니페스트에 채워주세요"
  value       = module.edge.alb_certificate_note
}

output "cloudfront_acm_certificate_arn" {
  description = "us-east-1에 발급된 CloudFront 전용 ACM 인증서"
  value       = module.edge.cloudfront_acm_certificate_arn
}

output "waf_web_acl_arn" {
  value = module.edge.waf_web_acl_arn
}

output "origin_verify_secret_arn" {
  description = "X-Origin-Verify 헤더 값 — K8s Secret으로 주입해서 애플리케이션 필터가 검증"
  value       = module.edge.origin_verify_secret_arn
}

output "alb_security_group_id" {
  description = "k8s/ingress-with-origin-verify.yaml의 alb.ingress.kubernetes.io/security-groups 값으로 채우세요"
  value       = module.edge.alb_security_group_id
}

output "cloudfront_domain_name" {
  description = "origin_alb_domain_name 채운 뒤 apply해야 값이 생김"
  value       = module.edge.cloudfront_domain_name
}

output "frontend_url" {
  value = module.frontend.frontend_url
}

output "frontend_s3_bucket_name" {
  value = module.frontend.s3_bucket_name
}

output "frontend_cloudfront_domain_name" {
  value = module.frontend.cloudfront_domain_name
}

output "frontend_acm_certificate_arn" {
  description = "us-east-1에 발급된 프론트엔드 전용 ACM 인증서"
  value       = module.frontend.acm_certificate_arn
}
