# ------------------------------------------------------------------------
# Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License").
# You may not use this file except in compliance with the License.
# A copy of the License is located at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# or in the "license" file accompanying this file. This file is distributed
# on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing
# permissions and limitations under the License.
# -------------------------------------------------------------------------

variable "test_id" {
  default = "dummy-123"
}

variable "kube_directory_path" {
    default = "./.kube"
}

variable "aws_region" {
  default = "<e.g. us-east-1>"
}

variable "eks_cluster_name" {
  default = "<cluster-name>"
}

variable "eks_cluster_context_name" {
  default = "<region>.<cluster-name>"
}

variable "test_namespace" {
  default = "sample-app-namespace"
}

variable "service_account_aws_access" {
  default = "sample-app-service-account"
}

variable "sample_app_image" {
  default = "<ECR_IMAGE_LINK>:<TAG>"
}

variable "sample_remote_app_image" {
  default = "<ECR_IMAGE_LINK>:<TAG>"
}