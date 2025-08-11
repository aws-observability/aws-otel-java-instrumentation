## API Gateway + Lambda Sample Application

The directory contains the source code and the Infrastructure as Code (IaC) to create the sample app in your AWS account.

### Prerequisite
Before you begin, ensure you have the following installed:
- Java 17
- Gradle
- Terraform
- AWS CLI configured with appropriate credentials

### Getting Started

#### 1. Build the application
```bash
# Change to the project directory
cd sample-apps/apigateway-lambda

# Build the application using Gradle
gradle clean build

# Prepare the Lambda deployment package
gradle createLambdaZip
```

#### 2. Deploy the application
```bash
# Change to the terraform directory
cd terraform

# Initialize Terraform
terraform init

# (Optional) Review the deployment plan for better understanding of the components
terraform plan

# Deploy
terraform apply
```

#### 3. Testing the applicating
After successful deployment, Terraform will output the API Gateway endpoint URL. You can test the application using:
```bash
curl <API_Gateway_URL>
```

#### 4. Clean Up
To avoid incurring unnecessary charges, remember to destroy the resources when you are done:
```bash
terraform destroy
```

#### (Optional) Instrumenting with Application Signals Lambda Layer
You can choose to instrument the Lambda function with Application Signals Lambda Layer upon deployment by passing in the layer ARN to the `adot_layer_arn` variable.
You must have the layer already published to your account before executing the following command.
```bash
terraform apply -var "adot_layer_arn=<APPLICATION_SIGNALS_LAYER_FULL_ARN_WITH_VERSION>"
```