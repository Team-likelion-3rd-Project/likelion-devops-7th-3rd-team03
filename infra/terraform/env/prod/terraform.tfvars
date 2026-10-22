environment  = "prod"
cluster_name = "snipy-cluster"

# 가용성 우선 사이징 (운영 기준)
single_nat_gateway                     = false
node_instance_type                     = "m5.large"
rds_instance_class                     = "db.m6g.large"
node_min_size                          = 3
node_max_size                          = 5
node_desired_size                      = 3
rds_multi_az                           = true
rds_deletion_protection                = true
rds_skip_final_snapshot                = false
elasticache_num_cache_clusters         = 2
elasticache_automatic_failover_enabled = true
elasticache_multi_az_enabled           = true

# apply를 실행하는 사람은 자동으로 관리자가 되므로 여기 넣지 않는다 (중복 시 실패).
# 다른 팀원의 IAM 사용자/역할 ARN을 넣는다. 예: "arn:aws:iam::<계정 ID>:user/<이름>"
cluster_admin_arns = []

domain_name           = "snipy.life"
waf_geo_match_enabled = true
waf_allowed_countries = ["KR"]

# 1단계 apply: 아래를 비워둔 채로 적용 (ALB가 아직 없어서 CloudFront/ALB 조회 리소스는 스킵됨)
# 2단계: k8s Ingress 배포 후 "alb.snipy.life"를 넣으면 됨
# 2단계 전에 "alb.snipy.life"를 넣으면 안 됨 — 그건 이 apply가 만드는 결과물이라 아직 존재하지 않음.
origin_alb_domain_name = "alb.snipy.life"
