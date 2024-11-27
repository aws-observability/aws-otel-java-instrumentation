variable "function_name" {
  type        = string
  description = "Name of sample app function"
  default     = "aws-opentelemetry-distro-java"
}

variable "architecture" {
  type        = string
  description = "Lambda function architecture, either arm64 or x86_64"
  default     = "x86_64"
}

variable "runtime" {
  type        = string
  description = "Java runtime version used for Lambda Function"
  default     = "java17"
}

variable "lambda_tracing_mode" {
  type        = string
  description = "Lambda function tracing mode"
  default     = "Active"
}

variable "api_gateway_name" {
  type        = string
  description = "Name of API gateway to create"
  default     = "aws-opentelemetry-distro-java"
}

variable "apigw_tracing_enabled" {
  type        = string
  description = "API Gateway REST API tracing enabled or not"
  default     = "true"
}