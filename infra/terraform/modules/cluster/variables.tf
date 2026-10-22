variable "cluster_name" {
  description = "EKS cluster name"
  type        = string
}

variable "cluster_version" {
  description = "Kubernetes version"
  type        = string
}

variable "cluster_admin_arns" {
  description = "EKS admin IAM user arn list"
  type        = list(string)
}

variable "aws_region" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_ids" {
  description = "노드/클러스터를 배치할 서브넷 (프라이빗 서브넷)"
  type        = list(string)
}

variable "node_instance_type" {
  description = "EKS managed node group 인스턴스 타입"
  type        = string
}

variable "node_min_size" {
  type = number
}

variable "node_max_size" {
  type = number
}

variable "node_desired_size" {
  type = number
}

variable "domain_name" {
  description = "external-dns가 관리할 도메인 (이 도메인 아래 레코드만 만든다)"
  type        = string
}

variable "route53_zone_id" {
  description = "external-dns가 레코드를 쓸 수 있는 유일한 호스팅 존"
  type        = string
}