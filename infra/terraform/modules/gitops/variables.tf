variable "cluster_name" {
  type = string
}

variable "aws_region" {
  type = string
}

variable "oidc_provider_arn" {
  type = string
}

variable "oidc_provider" {
  type = string
}

variable "argocd_repo_url" {
  description = "ArgoCD가 prod 앱 매니페스트를 가져올 Git 저장소"
  type        = string
  default     = "https://github.com/Team-likelion-3rd-Project/likelion-devops-7th-3rd-team03.git"
}

variable "argocd_target_revision" {
  description = "ArgoCD가 prod 앱을 가져올 브랜치. 운영은 main(릴리스 PR 머지 = 배포)"
  type        = string
  default     = "main"
}