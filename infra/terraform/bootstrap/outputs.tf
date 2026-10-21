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
