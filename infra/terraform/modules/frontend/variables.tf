variable "cluster_name" {
  type = string
}

variable "domain_name" {
  description = "프론트엔드 정적 호스팅 전체 도메인 (예: app.snipy.life, app.dev.snipy.life)"
  type        = string
}

variable "route53_zone_name" {
  description = "Route53에 실제 등록된 apex 도메인 (zone 조회 전용, modules/edge와 동일 zone 공유)"
  type        = string
}
