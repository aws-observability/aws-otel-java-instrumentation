terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}

# Define the provider for AWS
provider "aws" {}

resource "aws_default_vpc" "default" {}

resource "tls_private_key" "ssh_key" {
  algorithm = "RSA"
  rsa_bits = 4096
}

resource "aws_key_pair" "aws_ssh_key" {
  key_name = "instance_key-${var.test_id}"
  public_key = tls_private_key.ssh_key.public_key_openssh
}

locals {
  ssh_key_name        = aws_key_pair.aws_ssh_key.key_name
  private_key_content = tls_private_key.ssh_key.private_key_pem
}

data "aws_ami" "ami" {
  owners = ["amazon"]
  most_recent      = true
  filter {
    name   = "name"
    values = ["al20*-ami-minimal-*-x86_64"]
  }
  filter {
    name   = "state"
    values = ["available"]
  }
  filter {
    name   = "architecture"
    values = ["x86_64"]
  }
  filter {
    name   = "image-type"
    values = ["machine"]
  }

  filter {
    name   = "root-device-name"
    values = ["/dev/xvda"]
  }

  filter {
    name   = "root-device-type"
    values = ["ebs"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

resource "aws_instance" "main_service_instance" {
  ami                                   = data.aws_ami.ami.id # Amazon Linux 2 (free tier)
  instance_type                         = "t2.micro"
  key_name                              = local.ssh_key_name
  iam_instance_profile                  = var.test_role
  vpc_security_group_ids                = [aws_default_vpc.default.default_security_group_id]
  associate_public_ip_address           = true
  instance_initiated_shutdown_behavior  = "terminate"
  metadata_options {
    http_tokens = "required"
  }

  tags = {
    Name = "main-service-${var.test_id}"
  }
}

resource "null_resource" "main_service_setup" {
  connection {
    type = "ssh"
    user = var.user
    private_key = local.private_key_content
    host = aws_instance.main_service_instance.public_ip
  }

  provisioner "remote-exec" {
    inline = [
      # Install Java 11 and wget
      "sudo yum install wget java-11-amazon-corretto -y",

      # Copy in CW Agent configuration
      "agent_config='${replace(replace(file("./amazon-cloudwatch-agent.json"), "/\\s+/", ""), "$REGION", var.aws_region)}'",
      "echo $agent_config > amazon-cloudwatch-agent.json",

      # Get and run CW agent rpm
      "wget -O cw-agent.rpm ${var.cw_agent_rpm}",
      "sudo rpm -U ./cw-agent.rpm",
      "sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl -a fetch-config -m ec2 -s -c file:./amazon-cloudwatch-agent.json",

      # Get ADOT
      "wget -O adot.jar ${var.adot_jar}",

      # Get and run the sample application with configuration
      "aws s3 cp ${var.sample_app_jar} ./main-service.jar",

      "JAVA_TOOL_OPTIONS=' -javaagent:/home/ec2-user/adot.jar' \\",
      "OTEL_METRICS_EXPORTER=none \\",
      "OTEL_SMP_ENABLED=true \\",
      "OTEL_AWS_SMP_EXPORTER_ENDPOINT=http://localhost:4315 \\",
      "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://localhost:4315 \\",
      "OTEL_RESOURCE_ATTRIBUTES=aws.hostedin.environment=EC2,service.name=sample-application-${var.test_id} \\",
      "nohup java -jar main-service.jar &> nohup.out &",

      # The application needs time to come up and reach a steady state, this should not take longer than 30 seconds
      "sleep 30"
    ]
  }

  depends_on = [aws_instance.main_service_instance]
}

resource "aws_instance" "remote_service_instance" {
  ami                                   = data.aws_ami.ami.id # Amazon Linux 2 (free tier)
  instance_type                         = "t2.micro"
  key_name                              = local.ssh_key_name
  iam_instance_profile                  = var.test_role
  vpc_security_group_ids                = [aws_default_vpc.default.default_security_group_id]
  associate_public_ip_address           = true
  instance_initiated_shutdown_behavior  = "terminate"
  metadata_options {
    http_tokens = "required"
  }

  tags = {
    Name = "remote-service-${var.test_id}"
  }
}

resource "null_resource" "remote_service_setup" {
  connection {
    type = "ssh"
    user = var.user
    private_key = local.private_key_content
    host = aws_instance.remote_service_instance.public_ip
  }

  provisioner "remote-exec" {
    inline = [
      # Install Java 11 and wget
      "sudo yum install wget java-11-amazon-corretto -y",

      # Copy in CW Agent configuration
      "agent_config='${replace(replace(file("./amazon-cloudwatch-agent.json"), "/\\s+/", ""), "$REGION", var.aws_region)}'",
      "echo $agent_config > amazon-cloudwatch-agent.json",

      # Get and run CW agent rpm
      "wget -O cw-agent.rpm ${var.cw_agent_rpm}",
      "sudo rpm -U ./cw-agent.rpm",
      "sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl -a fetch-config -m ec2 -s -c file:./amazon-cloudwatch-agent.json",

      # Get ADOT
      "wget -O adot.jar ${var.adot_jar}",

      # Get and run the sample application with configuration
      "aws s3 cp ${var.sample_remote_app_jar} ./remote-service.jar",

      "JAVA_TOOL_OPTIONS=' -javaagent:/home/ec2-user/adot.jar' \\",
      "OTEL_METRICS_EXPORTER=none \\",
      "OTEL_SMP_ENABLED=true \\",
      "OTEL_AWS_SMP_EXPORTER_ENDPOINT=http://localhost:4315 \\",
      "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://localhost:4315 \\",
      "OTEL_RESOURCE_ATTRIBUTES=aws.hostedin.environment=EC2,service.name=sample-remote-application-${var.test_id} \\",
      "nohup java -jar remote-service.jar &> nohup.out &",

      # The application needs time to come up and reach a steady state, this should not take longer than 30 seconds
      "sleep 30"
    ]
  }

  depends_on = [aws_instance.remote_service_instance]
}
