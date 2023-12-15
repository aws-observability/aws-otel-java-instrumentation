output "sample_app_main_service_public_dns" {
  value = aws_instance.main_service_instance.public_dns
}

output "sample_app_remote_service_public_ip" {
  value = aws_instance.remote_service_instance.public_ip
}