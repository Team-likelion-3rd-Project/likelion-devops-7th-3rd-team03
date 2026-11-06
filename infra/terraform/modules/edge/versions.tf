# CloudFront/WAF는 us-east-1에서만 생성 가능 — 호출부(env/*)의 aws.us_east_1
# aliased provider를 명시적으로 전달받아야 한다.
terraform {
  required_providers {
    aws = {
      source                = "hashicorp/aws"
      configuration_aliases = [aws.us_east_1]
    }
  }
}
