output "state_bucket_name" {
  description = "env/*/providers.tf의 backend.bucket 값과 대조해서 일치하는지 확인"
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
