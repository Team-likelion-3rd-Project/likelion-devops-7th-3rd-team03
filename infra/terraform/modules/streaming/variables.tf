variable "cluster_name" {
  type = string
}

variable "oidc_provider_arn" {
  description = "EKS OIDC provider ARN — IRSA trust policy에 사용"
  type        = string
}

variable "oidc_provider" {
  description = "OIDC provider URL (https:// 접두사 포함) — IRSA trust policy 조건에 사용"
  type        = string
}

variable "glue_database_name" {
  description = "Glue Catalog Database 이름 (미지정 시 클러스터명 기반으로 자동 생성)"
  type        = string
  default     = ""
}