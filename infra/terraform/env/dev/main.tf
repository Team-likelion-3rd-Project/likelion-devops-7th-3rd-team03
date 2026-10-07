module "network" {
  source = "../../modules/network"

  cluster_name       = var.cluster_name
  vpc_cidr           = var.vpc_cidr
  azs                = var.azs
  public_subnets     = var.public_subnets
  private_subnets    = var.private_subnets
  database_subnets   = var.database_subnets
  single_nat_gateway = var.single_nat_gateway
}

# ECR은 bootstrap이 소유 (prod와 공유). 이름이 cluster_name으로 구분되지
# 않는 리터럴이라 dev/prod가 각각 module "registry"를 호출하면 충돌하고,
# prod를 destroy해도 dev가 쓰는 이미지가 사라지면 안 되므로 env가 아닌
# bootstrap(state 버킷과 같은 레이어)이 소유한다.
data "aws_ecr_repository" "management_service" {
  name = "management-service"
}

data "aws_ecr_repository" "redirect_service" {
  name = "redirect-service"
}

module "cluster" {
  source = "../../modules/cluster"

  cluster_name       = var.cluster_name
  cluster_version    = var.cluster_version
  aws_region         = var.aws_region
  vpc_id             = module.network.vpc_id
  subnet_ids         = module.network.private_subnets
  node_instance_type = var.node_instance_type
  node_min_size      = var.node_min_size
  node_max_size      = var.node_max_size
  node_desired_size  = var.node_desired_size
  cluster_admin_arns = var.cluster_admin_arns
}

module "database" {
  source = "../../modules/database"

  cluster_name                           = var.cluster_name
  vpc_id                                 = module.network.vpc_id
  database_subnet_group_name             = module.network.database_subnet_group_name
  database_subnets                       = module.network.database_subnets
  node_security_group_id                 = module.cluster.node_security_group_id
  elasticache_node_type                  = var.elasticache_node_type
  rds_instance_class                     = var.rds_instance_class
  rds_multi_az                           = var.rds_multi_az
  elasticache_num_cache_clusters         = var.elasticache_num_cache_clusters
  elasticache_automatic_failover_enabled = var.elasticache_automatic_failover_enabled
  elasticache_multi_az_enabled           = var.elasticache_multi_az_enabled
}

module "streaming" {
  source = "../../modules/streaming"

  cluster_name       = var.cluster_name
  oidc_provider_arn  = module.cluster.oidc_provider_arn
  oidc_provider      = module.cluster.oidc_provider
  glue_database_name = "snipy_click_logs"
}

module "edge" {
  source = "../../modules/edge"
  providers = {
    aws.us_east_1 = aws.us_east_1
  }

  route53_zone_name      = "snipy.life" # dev/prod 공통 — 실제 등록된 apex 도메인
  cluster_name           = var.cluster_name
  domain_name            = var.domain_name
  vpc_id                 = module.network.vpc_id
  node_security_group_id = module.cluster.node_security_group_id
  waf_geo_match_enabled  = var.waf_geo_match_enabled
  waf_allowed_countries  = var.waf_allowed_countries
  origin_alb_domain_name = var.origin_alb_domain_name
}

module "frontend" {
  source = "../../modules/frontend"
  providers = {
    aws.us_east_1 = aws.us_east_1
  }

  cluster_name      = var.cluster_name
  domain_name       = "app.${var.domain_name}"
  route53_zone_name = "snipy.life" # dev/prod 공통 — 실제 등록된 apex 도메인 (modules/edge와 동일)
}

module "gitops" {
  source = "../../modules/gitops"

  cluster_name      = var.cluster_name
  aws_region        = var.aws_region
  oidc_provider_arn = module.cluster.oidc_provider_arn
  oidc_provider     = module.cluster.oidc_provider

  depends_on = [module.cluster]
}

module "app_secrets" {
  source = "../../modules/app-secrets"

  cluster_name = var.cluster_name
}

resource "kubernetes_config_map" "management_service_config" {
  metadata {
    name      = "management-service-config"
    namespace = "default"
  }

  data = {
    DB_HOST                            = split(":", module.database.rds_endpoint)[0]
    DB_PORT                            = split(":", module.database.rds_endpoint)[1]
    DB_NAME                            = module.database.rds_db_name
    DB_APP_USER                        = "shortlink_app"
    REDIS_HOST                         = module.database.elasticache_primary_endpoint
    REDIS_PORT                         = "6379"
    REDIRECT_CACHE_TTL                 = "10m"
    KAKAO_REDIRECT_URI                 = "https://app.${var.domain_name}/auth/kakao/callback"
    JWT_ACCESS_TOKEN_VALIDITY_SECONDS  = "1800"
    JWT_REFRESH_TOKEN_VALIDITY_SECONDS = "1209600"
    SHORT_URL_BASE_URL                 = "https://${var.domain_name}"
    CORS_ALLOWED_ORIGINS               = "https://app.${var.domain_name}"
  }

  depends_on = [module.database]
}

resource "kubernetes_config_map" "redirect_service_config" {
  metadata {
    name      = "redirect-service-config"
    namespace = "default"
  }

  data = {
    DB_HOST     = split(":", module.database.rds_endpoint)[0]
    DB_PORT     = split(":", module.database.rds_endpoint)[1]
    DB_NAME     = module.database.rds_db_name
    DB_APP_USER = "shortlink_app"
    REDIS_HOST  = module.database.elasticache_primary_endpoint
    REDIS_PORT  = "6379"
    FIREHOSE_STREAM_NAME = module.streaming.firehose_delivery_stream_name
    AWS_REGION           = var.aws_region

    DB_HIKARI_MAX_POOL_SIZE = "8"
    # 컨테이너 메모리 limit 대비 %가 아니라 절대값 고정 — 리소스 사이징 계산이
    # 절대 힙 크기(MB) 기준이라 컨테이너 limit을 바꿔도 힙이 같이 흔들리면 안 됨.
    JAVA_TOOL_OPTIONS = "-Xms512m -Xmx512m"
  }

  depends_on = [module.database]
}