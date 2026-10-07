variable "cluster_name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "database_subnet_group_name" {
  type = string
}

variable "database_subnets" {
  type = list(string)
}

variable "node_security_group_id" {
  description = "EKS 노드 SG — RDS/ElastiCache 인바운드 허용 대상"
  type        = string
}

variable "elasticache_node_type" {
  description = "ElastiCache 노드 타입"
  type        = string
}

variable "rds_instance_class" {
  type    = string
  default = "db.t3.micro"
}

variable "rds_engine_version" {
  type    = string
  default = "8.0"
}

variable "rds_allocated_storage" {
  type    = number
  default = 20
}

variable "rds_max_allocated_storage" {
  type    = number
  default = 50
}

variable "rds_multi_az" {
  type    = bool
  default = true
}

variable "rds_backup_retention_period" {
  type    = number
  default = 1
}

variable "rds_deletion_protection" {
  type    = bool
  default = false
}

variable "elasticache_num_cache_clusters" {
  description = "1이면 단일 노드(dev용 비용 절감), 2 이상이면 Primary+Replica"
  type        = number
  default     = 2
}

variable "elasticache_automatic_failover_enabled" {
  description = "num_cache_clusters가 1이면 반드시 false여야 함 (AWS 제약)"
  type        = bool
  default     = true
}

variable "elasticache_multi_az_enabled" {
  type    = bool
  default = true
}
