terraform {
  required_version = ">= 1.10" # backend의 use_lockfile(S3 네이티브 락)이 1.10+ 전용

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    helm = {
      source  = "hashicorp/helm"
      version = "~> 2.12"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.25"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  backend "s3" {
    bucket       = "snipy-terraform-state-834922934330"
    key          = "env/prod/terraform.tfstate"
    region       = "ap-southeast-1"
    encrypt      = true
    use_lockfile = true # Terraform 1.10+ 네이티브 S3 잠금, DynamoDB 안 써도 됨
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "snipy"
      Environment = var.environment
      Team        = "team03"
      ManagedBy   = "terraform"
    }
  }
}

# CloudFront용 ACM 인증서/WAF(CLOUDFRONT scope)는 AWS 제약상 반드시 us-east-1에서 생성해야 함
# (실제 서비스 리전은 ap-southeast-1 그대로 유지, 이 프로바이더는 CloudFront 관련 리소스 전용)
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = {
      Project     = "snipy"
      Environment = var.environment
      Team        = "team03"
      ManagedBy   = "terraform"
    }
  }
}

# EKS 클러스터 생성 후 kubectl/helm이 해당 클러스터를 바라보도록 연결
provider "kubernetes" {
  host                   = module.cluster.cluster_endpoint
  cluster_ca_certificate = base64decode(module.cluster.cluster_certificate_authority_data)
  exec {
    api_version = "client.authentication.k8s.io/v1beta1"
    command     = "aws"
    args        = ["eks", "get-token", "--cluster-name", var.cluster_name, "--region", var.aws_region]
  }
}

provider "helm" {
  kubernetes {
    host                   = module.cluster.cluster_endpoint
    cluster_ca_certificate = base64decode(module.cluster.cluster_certificate_authority_data)
    exec {
      api_version = "client.authentication.k8s.io/v1beta1"
      command     = "aws"
      args        = ["eks", "get-token", "--cluster-name", var.cluster_name, "--region", var.aws_region]
    }
  }
}
