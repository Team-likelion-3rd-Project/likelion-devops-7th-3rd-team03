variable "aws_region" {
  type    = string
  default = "ap-southeast-1"
}

variable "cluster_name" {
  description = "env/prod의 cluster_name과 같은 값. prod ESO가 읽을 수 있는 시크릿 이름 접두사로 쓴다"
  type        = string
  default     = "snipy-cluster"
}