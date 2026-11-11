# 부하 테스트 시작 사양. terraform.tfvars(운영 사양) 위에 덮어쓴다:
#   terraform plan  -var-file=test-lite.tfvars
#   terraform apply -var-file=test-lite.tfvars
# 목표(load-test/SCENARIOS.md)를 통과하는 가장 작은 사양을 찾는다.
# 병목이 나온 자원만 한 단계씩 올린다 (docs/load-test-plan-prod.md).
# 가용성(NAT·RDS·Redis 이중화)은 성능과 관계없어 여기서는 끈다.

single_nat_gateway = true # NAT Gateway 1개를 두 AZ가 공유

# m6g(Graviton)는 앱 이미지가 amd64 전용이라 쓸 수 없다.
# 노드 3대: t3.medium은 노드당 가용 메모리가 ~3.5GiB라 상주 파드
# (ArgoCD·모니터링·ESO·앱)가 2대에는 들어가지 않는다 (2차 dev 실측).
# 최대 5: HPA 최대(앱 requests 4 vCPU)에서 cluster-autoscaler가 노드를 늘리는지 본다.
node_instance_type = "t3.medium"
node_min_size      = 3
node_max_size      = 5
node_desired_size  = 3

# t4g.micro(max_connections ~80)는 HPA 최대 연결 수(~105, #174)보다 작다.
rds_instance_class          = "db.t4g.medium"
rds_multi_az                = false
rds_backup_retention_period = 1

# 테스트 후 destroy로 바로 정리할 수 있게 삭제 보호와 최종 스냅샷을 끈다
rds_deletion_protection = false
rds_skip_final_snapshot = true

elasticache_num_cache_clusters         = 1 # Primary만
elasticache_automatic_failover_enabled = false
elasticache_multi_az_enabled           = false
