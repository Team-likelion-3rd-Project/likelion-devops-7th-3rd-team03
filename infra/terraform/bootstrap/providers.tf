# backend 블록 없음 — 로컬 state를 그대로 쓴다 (main.tf 상단 주석 참고).
# use_lockfile은 Terraform 1.10+ 전용 기능이라 env/*와 동일하게 버전 하한을 맞춘다.
terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project   = "snipy"
      ManagedBy = "terraform-bootstrap"
    }
  }
}
