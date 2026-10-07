variable "cluster_name" {
  description = "리소스 이름 접두사로 사용"
  type        = string
}

variable "vpc_cidr" {
  description = "VPC CIDR block"
  type        = string
}

variable "azs" {
  description = "Availability zones (2개, HA 구성)"
  type        = list(string)
}

variable "public_subnets" {
  description = "퍼블릭 서브넷 CIDR — NAT Gateway, ALB"
  type        = list(string)
}

variable "private_subnets" {
  description = "프라이빗 서브넷 CIDR — EKS 노드"
  type        = list(string)
}

variable "database_subnets" {
  description = "DB 전용 서브넷 CIDR — RDS, ElastiCache"
  type        = list(string)
}

variable "single_nat_gateway" {
  description = "true면 NAT Gateway 1개를 모든 AZ가 공유 (dev용 비용 절감). false면 AZ당 1개(가용성 우선, prod 기본값)"
  type        = bool
  default     = false
}
