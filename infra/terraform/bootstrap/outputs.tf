output "state_bucket_name" {
  description = "env/prod terraform init -backend-config=\"bucket=...\"에 넘길 값"
  value       = aws_s3_bucket.tfstate.bucket
}

output "state_bucket_arn" {
  value = aws_s3_bucket.tfstate.arn
}

output "management_service_ecr_url" {
  value = module.registry.management_service_ecr_url
}

output "redirect_service_ecr_url" {
  value = module.registry.redirect_service_ecr_url
}

output "oauth_secret_name" {
  description = "소셜 로그인 OAuth 값을 넣을 시크릿 (aws secretsmanager put-secret-value --secret-id <이 값>)"
  value       = aws_secretsmanager_secret.oauth.name
}

output "slack_webhook_secret_name" {
  description = "prod Slack 웹훅 URL을 넣을 Secrets Manager 시크릿 이름 (README 3-6)"
  value       = aws_secretsmanager_secret.slack_webhook.name
}

output "onprem_backup_bucket_name" {
  description = "온프렘 cp1의 백업 업로드 대상 버킷 (ON 담당자에게 전달)"
  value       = aws_s3_bucket.onprem_backup.bucket
}

output "onprem_backup_prefix" {
  description = "업로드 경로 접두사. 이 아래 etcd/, mysql/에 올린다"
  value       = "onprem/${var.onprem_cluster_name}/"
}

output "onprem_backup_user_name" {
  description = "액세스 키를 발급할 IAM 사용자 (aws iam create-access-key --user-name <이 값>)"
  value       = aws_iam_user.onprem_backup.name
}