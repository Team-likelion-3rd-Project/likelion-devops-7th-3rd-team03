# 동작 확인용 축소 사양. terraform.tfvars(운영 사양) 위에 덮어쓴다:
#   terraform plan  -var-file=test-lite.tfvars
#   terraform apply -var-file=test-lite.tfvars
# 운영 사양으로 올릴 때는 -var-file 없이 같은 state에서 다시 apply한다
# (RDS 인스턴스 변경·Multi-AZ 전환 중에는 잠시 연결이 끊길 수 있다).
# 가용성(AZ 이중화)과 성능은 확인하지 못하므로 부하테스트에는 쓰지 않는다.

single_nat_gateway = true # NAT Gateway 1개를 두 AZ가 공유

# m6g(Graviton)는 앱 이미지가 amd64 전용이라 쓸 수 없다.
# 노드 3대: t3.medium은 노드당 가용 메모리가 ~3.5GiB라 상주 파드
# (ArgoCD·모니터링·ESO·앱)가 2대에는 들어가지 않는다 (2차 dev 실측).
node_instance_type = "t3.medium"
node_min_size      = 3
node_max_size      = 4
node_desired_size  = 3

# 기능 확인용 최소 사양 (부하테스트 때 버퍼풀 부족으로 병목이었던 이력이 있음)
rds_instance_class = "db.t4g.micro"
rds_multi_az       = false

elasticache_num_cache_clusters         = 1 # Primary만
elasticache_automatic_failover_enabled = false
elasticache_multi_az_enabled           = false
