variable "cluster_name" {
  type = string
}

variable "kakao_client_id" {
  type      = string
  sensitive = true
}

variable "kakao_client_secret" {
  type      = string
  sensitive = true
}

variable "google_client_id" {
  type      = string
  sensitive = true
}

variable "google_client_secret" {
  type      = string
  sensitive = true
}

variable "naver_client_id" {
  type      = string
  sensitive = true
}

variable "naver_client_secret" {
  type      = string
  sensitive = true
}