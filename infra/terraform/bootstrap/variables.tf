variable "aws_region" {
  type    = string
  default = "ap-southeast-1"
}

variable "cluster_name" {
  description = "env/prod의 cluster_name과 같은 값. prod ESO가 읽을 수 있는 시크릿 이름 접두사로 쓴다"
  type        = string
  default     = "snipy-cluster"
}

variable "onprem_cluster_name" {
  description = "온프렘 백업을 올리는 클러스터 이름. 버킷 경로 onprem/<이 값>/에 쓴다"
  type        = string
  default     = "snipy-dev"

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.onprem_cluster_name))
    error_message = "소문자, 숫자, -만 쓸 수 있습니다."
  }
}

variable "onprem_backup_retention_days" {
  description = "온프렘 백업을 S3에 보관하는 일수. 로컬(cp1)은 최근 7개를 보관한다"
  type        = number
  default     = 14

  validation {
    condition     = var.onprem_backup_retention_days > 7
    error_message = "로컬 보관(7개)보다 길어야 오프사이트 백업의 의미가 있습니다."
  }
}

variable "onprem_backup_noncurrent_days" {
  description = "덮어써지거나 만료된 온프렘 백업의 이전 버전을 보관하는 일수. 이 기간 안에는 이전 버전으로 복구할 수 있다"
  type        = number
  default     = 7

  validation {
    condition     = var.onprem_backup_noncurrent_days >= 1
    error_message = "1일 이상이어야 합니다."
  }
}