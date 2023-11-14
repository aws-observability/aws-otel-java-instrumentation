# Demo Sample App Updating Guide

## Introduction:

The demo sample app is used to perform E2E testing on cloudwatch, cloudwatch operator and adot repository. If any changes need to be made on the demo sample app, the following steps should be taken.

## EKS Use Case: Uploading to ECR
Since the images are shared by three different repositories, care must be taken while updating the images so that none of the three repositories get left behind.
Ensure that none of the repositories are currently using the image about to be updated. If all images are being used, create a new image instead.
To update the image, first push the update to a backup image (or generate a new one), then switch the address on the three repositories to the backup image one by one. Once all three repositories are pointing to
the backup image, push the update to the main image and revert the addresses on the repositories back to the original. Be careful to ensure the image names are appropriately stored in secrets.

### Steps to update image:
1. Use `ada` commands to autheticate into the testing account
2. Login to ECR Repository: `aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin {REPOSITORY}`
3. Change repository name in the `build.gradle.kts` file under `testing/sample-apps/springboot` or `testing/sample-apps/sprintboot-remote-service`
4. Run `gradle jib` under the respective directory.

## [WIP] EC2 Use Case: Building the JAR Files
To build the JAR files of the sample application, simply `cd` into each application, e.g. `cd testing/sample-apps/springboot`, and run `gradle build`.
This will create a JAR file in the `build/libs/` folder. To update the JAR file in the testing account:
- Use `ada` commands to authenticate into the testing account
- Only after you're sure of your changes and if they do not break the tests running in other repos, use `aws s3api put-object --bucket <BUCKET_NAME> --body build/libs/<JAR_FILE_NAME>.jar --key <SERVICE_NAME>.jar` to push the JAR to S3
