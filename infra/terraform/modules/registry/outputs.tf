output "management_service_ecr_url" {
  value = aws_ecr_repository.management_service.repository_url
}

output "redirect_service_ecr_url" {
  value = aws_ecr_repository.redirect_service.repository_url
}

output "management_service_ecr_arn" {
  value = aws_ecr_repository.management_service.arn
}

output "redirect_service_ecr_arn" {
  value = aws_ecr_repository.redirect_service.arn
}
