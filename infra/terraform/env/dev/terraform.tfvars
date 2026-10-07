environment  = "dev"
cluster_name = "snipy-dev-cluster"

# staging 역할 겸용 — 원래는 prod와 동일한 HA/사이징 (prod 장애/스케일 특성을 그대로
# 재현하기 위함)이지만, viral-spike 부하테스트(#156)가 끝난 뒤 비용 절감을 위해 임시로
# 축소함. 다음 부하테스트 전에 반드시 원복할 것 (false / node 3·3·5 / db.m6g.large).
single_nat_gateway = true # AZ당 1개 → 1개로 임시 축소

# m6g(Graviton)는 앱 이미지가 amd64 전용이라 exec format error가 남 — 이미지
# 멀티아키 빌드 붙이기 전까지는 amd64 계열 유지.
# m5.large → t3.medium으로 임시 다운사이징 (2vCPU/4GiB, burstable). t3는 예전에
# 부하테스트 중 CPU 크레딧 고갈로 non-burstable(m5.large)로 바꾼 이력이 있으니
# 다음 부하테스트 전에는 반드시 m5.large로 원복할 것.
# 노드 대수는 3대 유지 — t3.medium은 노드당 가용 메모리가 작아서(~3.5GiB) 2대로는
# 현재 상주 파드(ArgoCD/모니터링/앱) 총 사용량(~9.15GiB)이 안 들어감. max=5는 유지해
# autoscaler가 필요시 늘리도록 둠.
node_instance_type = "t3.medium"
node_min_size      = 3
node_max_size      = 5
node_desired_size  = 3

# RDS는 관리형 바이너리라 ARM 이슈 없음 — Graviton으로 비용 절감.
# db.m6g.large → db.t4g.medium으로 임시 다운사이징 (4GiB RAM, burstable).
# t3.micro(1GiB)는 부하테스트 중 버퍼풀 부족으로 병목이었던 이력이 있으니,
# 다음 부하테스트 전에는 반드시 db.m6g.large로 원복할 것.
rds_instance_class = "db.t4g.medium"

# 부하테스트 기간 동안 Multi-AZ/레플리카는 비용만 나가고 필요 없어서 끔.
# 부하테스트 끝나면 다시 켜서 prod와 맞출 것.
rds_multi_az = false

elasticache_num_cache_clusters         = 1 # Primary만
elasticache_automatic_failover_enabled = false
elasticache_multi_az_enabled           = false

cluster_admin_arns = [
  "arn:aws:iam::834922934330:user/team03-user01",
  "arn:aws:iam::834922934330:user/team03-user02",
  "arn:aws:iam::834922934330:user/team03-user03",
  "arn:aws:iam::834922934330:user/team03-user04"
]

domain_name           = "dev.snipy.life"
waf_geo_match_enabled = true
waf_allowed_countries = ["KR"]

# 1단계 apply: 비워둔 채로 적용 (ALB가 아직 없어서 CloudFront/ALB 조회 리소스는 스킵됨)
# 2단계: k8s Ingress 배포 후 실제 ALB DNS 이름을 확인해 채우고 재적용 (prod와 동일한 절차)
origin_alb_domain_name = "alb.dev.snipy.life"
