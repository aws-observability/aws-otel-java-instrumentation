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

variable "aws_region" {
  default = "<aws-region>"
}

variable "user" {
  default = "ec2-user"
}

variable "sample_app_jar" {
  default = "s3://<bucket-name>/<jar>"
}

variable "sample_remote_app_jar" {
  default = "s3://<bucket-name>/<jar>"
}

variable "get_cw_agent_rpm" {
  default = "<command> s3://<bucket-name>/<jar>"
}

variable "get_adot_jar" {
  default = "<command> s3://<bucket-name>/<jar>"
}