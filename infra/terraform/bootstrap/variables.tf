variable "state_bucket_name" {
  description = "Terraform state를 저장할 S3 버킷 이름 — env/*/providers.tf의 backend.bucket과 반드시 일치해야 함"
  type        = string
  default     = "snipy-terraform-state-834922934330"
}

variable "aws_region" {
  type    = string
  default = "ap-southeast-1"
}
