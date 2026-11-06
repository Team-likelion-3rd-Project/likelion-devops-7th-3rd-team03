variable "cluster_name" {
  type = string
}

variable "domain_name" {
  description = "서비스 도메인 (Route53에 등록된 도메인)"
  type        = string
}

variable "vpc_id" {
  type = string
}

variable "node_security_group_id" {
  type = string
}

variable "waf_geo_match_enabled" {
  description = "Geo-Match Rule 활성화 여부 (서비스 대상국 외 접근 제한)"
  type        = bool
  default     = false
}

variable "waf_allowed_countries" {
  description = "Geo-Match Rule 허용 국가 코드 (waf_geo_match_enabled=true일 때만 사용)"
  type        = list(string)
  default     = ["KR"]
}

variable "route53_zone_name" {
  description = "Route53에 실제 등록된 apex 도메인 (zone 조회 전용)"
  type        = string
}
