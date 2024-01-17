# How to Test E2E Resource Changes
This guide will give a step by step instruction on how to test changes made to E2E testing resources before pushing a PR.
The guide will include the following:
- Setting up IAM roles and an EKS cluster
- Setting up VPC settings and IAM role for EC2 instances
- Buliding sample app images/files and putting them into ECRs/S3 buckets
- Forking a repository and setting up necessary secrets


### 1. Create an IAM Role with OIDC Identity Provider
This step is needed to allow Github Action to have access to resources in the AWS account
#### Create an OIDC Provider
- First step is to create an OIDC Identity Provider to allow Github action access to the AWS account resource. Login to AWS, go to the IAM console and click on the Identity Providers tab.
- Click on Add Provider, choose OpenID Connect and type `https://token.actions.githubusercontent.com` in the Provider URL. Click "Get thumbprint". For Audience, use `sts.amazonaws.com`. Finally, click "Add provider"
#### Create an IAM role
- Next, an IAM role needs to be created using the OIDC Identity Provider. Go to the Roles tab and click Create role. 
- Choose Web Identity, and choose `token.actions.githubusercontent.com` as the Identity provider, Audience as `sts.amazonaws.com`, and for Github organizations put your github username down. Click next.
- Add the AdministratorAccess policy. Click next.
- Enter your Role name. Click "Create role".
#### Add Additional Permission
- After the role is created, search the role name in the roles tab, click on the role, and go to the Trust relationships tab. Click on "Edit trust policy".
- In the Statement list, add the following item: 
`{
  "Sid": "accessToRole",
  "Effect": "Allow",
  "Principal": {
  "AWS": "arn:aws:iam::<AccountID>:root"
  },
  "Action": "sts:AssumeRole"
  }`. This additional permission is need to allow Github Action to assume roles and have access to the EKS cluster. 

Additional Resource: https://docs.github.com/en/actions/deployment/security-hardening-your-deployments/configuring-openid-connect-in-amazon-web-services

### 2. Create EKS Clusters
The E2E EKS test uses an EKS cluster to deploy the sample apps. 
#### Setup Environment with the Appropriate Roles and Permissions.
Note: Make sure to replace <AccountID> with the correct value.
- First, login to the testing account
- Assume the e2e test role by running 
  - `output=$(aws sts assume-role --role-arn arn:aws:iam::<AccountID>:role/<E2ETestRole> --role-session-name AWSCLI-Session)`
  - `export AWS_ACCESS_KEY_ID=$(echo $output | jq -r .Credentials.AccessKeyId)`
  - `export AWS_SECRET_ACCESS_KEY=$(echo $output | jq -r .Credentials.SecretAccessKey)`
  - `export AWS_SESSION_TOKEN=$(echo $output | jq -r .Credentials.SessionToken)`
- Run `aws sts get-caller-identity` to check if you are in the correct role
#### Create a new Cluster
Note: Make sure to replace <Region> with the correct value (e.g. us-east-1)
- Next, create the cluster by running `eksctl create cluster --name e2e-canary-test --region <Region> --zones <Region>a,<Region>b`. This will take around ~10 minutes. 
#### Install AWS Load Balancer Controller Add-on
- Finally, install the AWS Load Balancer Controller add-on by running the following commands. Make sure to replace the `<ClusterName>` and `<AccountID>` with the correct value.
  ```
  eksctl utils associate-iam-oidc-provider --cluster e2e-canary-test --region <Region> --approve
  curl -O https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/v2.4.7/docs/install/iam_policy.json
  aws iam create-policy --policy-name AWSLoadBalancerControllerIAMPolicy --policy-document file://iam_policy.json --region <Region>
  eksctl create iamserviceaccount --cluster=e2e-canary-test --namespace=kube-system --name=aws-load-balancer-controller --attach-policy-arn=arn:aws:iam::<AccountID>:policy/AWSLoadBalancerControllerIAMPolicy --region <Region> --approve
  kubectl apply --validate=false -f https://github.com/jetstack/cert-manager/releases/download/v1.5.4/cert-manager.yaml
  curl -Lo v2_4_7_full.yaml https://github.com/kubernetes-sigs/aws-load-balancer-controller/releases/download/v2.4.7/v2_4_7_full.yaml
  sed -i.bak -e '561,569d' ./v2_4_7_full.yaml
  sed -i.bak -e 's|your-cluster-name|e2e-canary-test|' ./v2_4_7_full.yaml
  kubectl apply -f v2_4_7_full.yaml
  curl -Lo v2_4_7_ingclass.yaml https://github.com/kubernetes-sigs/aws-load-balancer-controller/releases/download/v2.4.7/v2_4_7_ingclass.yaml
  kubectl apply -f v2_4_7_ingclass.yaml```
#### Create a New Cluster and Install the AWS Load Balancer Controller Add-on for All Regions
Repeat the previous two steps for all 5 regions. (us-east-1, us-east-2, eu-west-1, ap-northeast-1, ap-southeast-2)

Note: If you do not want to test all 5 regions, you can create one for us-east-1, then on the [appsignals-e2e-eks-canary-test.yml](.github/workflows/appsignals-e2e-eks-canary-test.yml) comment out the other regions on line 24

### 3. Setting up Environment for EC2 Tests
#### Create IAM Role for EC2 Instance
- Login to AWS, go to the IAM console and click on the Roles tab. Click Create role.
- Choose AWS service, and choose EC2 as the use case. Click Next.
- Choose AmazonS3ReadOnlyAccess, AWSXrayWriteOnlyAccess, and CloudWatchAgentServerPolicy as the permission. 
- Type the role name as `APP_SIGNALS_EC2_TEST_ROLE` and click "Create role".

#### Setting Up Default VPC
- Go to the VPC console and on the routing table for the default VPC, click Edit routes. (The default VPC should have the `-` name if it hasn't been assigned to another VPC before)
- Click add routes, for destination add `0.0.0.0/0`, for target add Internet Gateway and save changes.
- Go to the Security groups tab, find the security group attached to the default VPC, click Edit inbound rules, choose type: All Traffic, Source: custom, and CIDR block: 0.0.0.0/0. Save rules.

### 4. Building Sample App to ECR
Create two ECR repositories: one for the sample app main service and another for the sample app remote service. 
Follow the instructions [here](./sample-apps/README.md) to build the sample app image and upload it to the ECR

### 5. Building Sample App to S3 Bucket
Create an S3 Bucket to store the .jar files for the sample app main service and sample app remote service.
Follow the instructions under [here](./sample-apps/README.md) to build the sample app .jar and upload it to the bucket

### 6. Setting up repository
- Go to https://github.com/aws-observability/aws-otel-java-instrumentation and create a fork
- Go to the forked repo and enable action on the Action tab
- Add the following secrets to the repository
  - APP_SIGNALS_E2E_TEST_ACC: `<AccountID>`
  - E2E_TEST_ROLE_ARN: `arn:aws:iam::<AccountID>:role/<RoleName>`
  - APP_SIGNALS_E2E_FE_SA_IMG: `<AccountID>.dkr.ecr.us-east-1.amazonaws.com/<Path to Sample App Image>`
  - APP_SIGNALS_E2E_RE_SA_IMG: `<AccountID>.dkr.ecr.us-east-1.amazonaws.com/<Path to Remote Sample App Image>`
  - APP_SIGNALS_E2E_FE_SA_JAR: s3://<BucketName>/<FileName.jar>
  - APP_SIGNALS_E2E_RE_SA_JAR: s3://<BucketName>/<FileName.jar>


### 7. Running the tests
Go to the Actions tab on the forked repository.

- To run the EKS test, go to `App Signals Enablement - E2E EKS Canary Testing`, and click `Enable Workflow`, then `Run Workflow`.
- To run the EC2 test, go to `App Signals Enablement - E2E EC2 Canary Testing`, and click `Enable Workflow`, then `Run Workflow`.

If you don't want the canaries to run every 15 minutes on the forked repository, click the `...` button on the top right and choose `Disable Workflow`

### E2E Testing Resources
- `./.github/workflows/appsignals-e2e-*`: workflow files for running e2e tests
- `./testing/sample-apps/*`: files for building the sample app
- `./testing/validator/*`: files for validating logs/metrics/traces generated by sample app
- `./testing/terraform/*`:  files for launching the sample app to EKS cluster or EC2 instances