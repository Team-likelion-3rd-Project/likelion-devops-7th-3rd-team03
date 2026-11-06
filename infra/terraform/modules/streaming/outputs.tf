output "firehose_delivery_stream_name" {
  description = "redirect-service가 FIREHOSE_STREAM_NAME 환경변수로 사용"
  value       = aws_kinesis_firehose_delivery_stream.click_events.name
}

output "firehose_delivery_stream_arn" {
  value = aws_kinesis_firehose_delivery_stream.click_events.arn
}

output "click_logs_bucket" {
  value = aws_s3_bucket.click_logs.bucket
}

output "athena_results_bucket" {
  value = aws_s3_bucket.athena_results.bucket
}

output "athena_workgroup" {
  value = aws_athena_workgroup.snipy.name
}

output "glue_database_name" {
  value = aws_glue_catalog_database.snipy.name
}

output "redirect_service_irsa_role_arn" {
  description = "redirect-service의 ServiceAccount에 이 ARN을 annotation으로 연결"
  value       = aws_iam_role.redirect_service.arn
}

output "athena_cronjob_irsa_role_arn" {
  description = "Athena 배치 CronJob의 ServiceAccount에 이 ARN을 annotation으로 연결"
  value       = aws_iam_role.athena_cronjob.arn
}
