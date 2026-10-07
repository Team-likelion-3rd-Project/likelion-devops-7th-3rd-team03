module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.0"

  name = "${var.cluster_name}-vpc"
  cidr = var.vpc_cidr

  azs              = var.azs
  public_subnets   = var.public_subnets
  private_subnets  = var.private_subnets
  database_subnets = var.database_subnets

  # NAT Gateway: single_nat_gateway=false일 때만 one_nat_gateway_per_az가 적용됨
  # (prod 기본값 = AZ당 1개로 가용성 우선, dev는 var로 1개 공유해 비용 절감)
  enable_nat_gateway     = true
  single_nat_gateway     = var.single_nat_gateway
  one_nat_gateway_per_az = true

  # RDS/ElastiCache Subnet Group 자동 생성
  create_database_subnet_group = true

  enable_dns_hostnames = true
  enable_dns_support   = true

  # EKS가 서브넷을 자동으로 찾을 수 있도록 태그 부여
  public_subnet_tags = {
    "kubernetes.io/cluster/${var.cluster_name}" = "shared"
    "kubernetes.io/role/elb"                    = "1"
  }

  private_subnet_tags = {
    "kubernetes.io/cluster/${var.cluster_name}" = "shared"
    "kubernetes.io/role/internal-elb"           = "1"
  }
}
