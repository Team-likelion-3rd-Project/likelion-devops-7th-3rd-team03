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

# EKS 관리자 (kubectl). apply를 실행하는 주체도 반드시 넣는다 (modules/cluster 주석 참고).
#   snipy-terraform-apply: terraform CI(.github/workflows/terraform.yml)가 apply할 때 쓰는 역할
#   team03: 로컬 예비 apply와 kubectl 확인용 IAM 사용자
# 팀원이 늘면 IAM 사용자 ARN을 추가한다.
cluster_admin_arns = [
  "arn:aws:iam::834922934330:role/snipy-terraform-apply",
  "arn:aws:iam::834922934330:user/team03",
]

domain_name           = "snipy.life"
waf_geo_match_enabled = true
waf_allowed_countries = ["KR"]

