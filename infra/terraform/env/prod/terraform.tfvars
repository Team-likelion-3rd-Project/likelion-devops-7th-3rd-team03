environment  = "prod"
cluster_name = "snipy-cluster"

# 운영 사양. 부하 테스트 결과로 정했다 (docs/load-test-plan-prod.md 9·10절, #262).
# 성능: t3.medium 3대(최대 5) / db.t4g.medium / cache.t4g.micro로
#   Load 23, Stress 230, 바이럴 800 TPS를 통과했다.
# 가용성: NAT 2개, RDS Multi-AZ, Redis 복제본으로 AZ 하나가 장애 나도 동작하게 한다.
single_nat_gateway = false

# m6g(Graviton) 노드는 앱 이미지가 amd64 전용이라 쓸 수 없다.
node_instance_type = "t3.medium"
node_min_size      = 3
node_max_size      = 5
node_desired_size  = 3

rds_instance_class      = "db.t4g.medium"
rds_multi_az            = true
rds_deletion_protection = true
rds_skip_final_snapshot = false

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

