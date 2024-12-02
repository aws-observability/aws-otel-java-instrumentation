### Lambda function
locals {
  architecture = var.architecture == "x86_64" ? "amd64" : "arm64"
}

resource "aws_iam_role" "lambda_role" {
  name               = "lambda_execution_role"
  assume_role_policy = jsonencode({
    Version = "2012-10-17",
    Statement = [{
      Action    = "sts:AssumeRole",
      Effect    = "Allow",
      Principal = { Service = "lambda.amazonaws.com" }
    }]
  })
}

resource "aws_iam_policy" "s3_access" {
  name        = "S3ListBucketsPolicy"
  description = "Allow Lambda to list S3 buckets"
  policy      = jsonencode({
    Version = "2012-10-17",
    Statement = [{
      Effect   = "Allow",
      Action   = ["s3:ListAllMyBuckets"],
      Resource = "*"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "attach_execution_role_policy" {
  role       = aws_iam_role.lambda_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy_attachment" "attach_s3_policy" {
  role       = aws_iam_role.lambda_role.name
  policy_arn = aws_iam_policy.s3_access.arn
}

resource "aws_iam_role_policy_attachment" "attach_xray_policy" {
  role       = aws_iam_role.lambda_role.name
  policy_arn = "arn:aws:iam::aws:policy/AWSXRayDaemonWriteAccess"
}

resource "aws_lambda_function" "sampleLambdaFunction" {
  function_name    = var.function_name
  runtime          = var.runtime
  timeout          = 300
  handler          = "com.amazon.sampleapp.LambdaHandler::handleRequest"
  role             = aws_iam_role.lambda_role.arn
  filename         = "${path.module}/../build/distributions/lambda-function.zip"
  source_code_hash = filebase64sha256("${path.module}/../build/distributions/lambda-function.zip")
  architectures    = [var.architecture]
  memory_size      = 512
  tracing_config {
    mode = var.lambda_tracing_mode
  }
  layers = var.adot_layer_arn != null && var.adot_layer_arn != "" ? [var.adot_layer_arn] : []
  environment {
    variables = var.adot_layer_arn != null && var.adot_layer_arn != "" ? {
      AWS_LAMBDA_EXEC_WRAPPER = "/opt/otel-instrument"
    } : {}
  }
}

### API Gateway proxy to Lambda function
resource "aws_api_gateway_rest_api" "apigw_lambda_api" {
  name = var.api_gateway_name
}

resource "aws_api_gateway_resource" "apigw_lambda_resource" {
  rest_api_id = aws_api_gateway_rest_api.apigw_lambda_api.id
  parent_id   = aws_api_gateway_rest_api.apigw_lambda_api.root_resource_id
  path_part   = "lambda"
}

resource "aws_api_gateway_method" "apigw_lambda_method" {
  rest_api_id   = aws_api_gateway_rest_api.apigw_lambda_api.id
  resource_id   = aws_api_gateway_resource.apigw_lambda_resource.id
  http_method   = "ANY"
  authorization = "NONE"
}

resource "aws_api_gateway_integration" "apigw_lambda_integration" {
  rest_api_id = aws_api_gateway_rest_api.apigw_lambda_api.id
  resource_id = aws_api_gateway_resource.apigw_lambda_resource.id
  http_method = aws_api_gateway_method.apigw_lambda_method.http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = aws_lambda_function.sampleLambdaFunction.invoke_arn
}

resource "aws_api_gateway_deployment" "apigw_lambda_deployment" {
  depends_on = [
    aws_api_gateway_integration.apigw_lambda_integration
  ]
  rest_api_id = aws_api_gateway_rest_api.apigw_lambda_api.id
}

resource "aws_api_gateway_stage" "test" {
  stage_name           = "default"
  rest_api_id          = aws_api_gateway_rest_api.apigw_lambda_api.id
  deployment_id        = aws_api_gateway_deployment.apigw_lambda_deployment.id
  xray_tracing_enabled = var.apigw_tracing_enabled
}

resource "aws_lambda_permission" "apigw_lambda_invoke" {
  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.sampleLambdaFunction.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.apigw_lambda_api.execution_arn}/*/*"
}

# Output the API Gateway URL
output "invoke_url" {
  value = "${aws_api_gateway_stage.test.invoke_url}/lambda"
}
