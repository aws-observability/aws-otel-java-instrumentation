terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }

    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = ">= 2.16.1"
    }

    kubectl = {
      source  = "gavinbunney/kubectl"
      version = ">= 1.7.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

# get eks cluster
data "aws_eks_cluster" "testing_cluster" {
    name = var.eks_cluster_name
}
data "aws_eks_cluster_auth" "testing_cluster" {
    name = var.eks_cluster_name
}

# set up kubectl
provider "kubernetes" {
  host = data.aws_eks_cluster.testing_cluster.endpoint
  cluster_ca_certificate = base64decode(data.aws_eks_cluster.testing_cluster.certificate_authority[0].data)
  token = data.aws_eks_cluster_auth.testing_cluster.token
}

provider "kubectl" {
  // Note: copy from eks module. Please avoid use shorted-lived tokens when running locally.
  // For more information: https://registry.terraform.io/providers/hashicorp/kubernetes/latest/docs#exec-plugins
  host                   = data.aws_eks_cluster.testing_cluster.endpoint
  cluster_ca_certificate = base64decode(data.aws_eks_cluster.testing_cluster.certificate_authority[0].data)
  token                  = data.aws_eks_cluster_auth.testing_cluster.token
  load_config_file       = false
}

data "template_file" "kubeconfig_file" {
  template = file("./kubeconfig.tpl")
  vars = {
    CLUSTER_NAME : var.eks_cluster_context_name
    CA_DATA : data.aws_eks_cluster.testing_cluster.certificate_authority[0].data
    SERVER_ENDPOINT : data.aws_eks_cluster.testing_cluster.endpoint
    TOKEN = data.aws_eks_cluster_auth.testing_cluster.token
  }
}

resource "local_file" "kubeconfig" {
  content = data.template_file.kubeconfig_file.rendered
  filename = "${var.kube_directory_path}/config"
}

### Setting up the sample app on the cluster

resource "kubernetes_deployment" "sample_app_deployment" {

  metadata {
    name      = "sample-app-deployment-${var.test_id}"
    namespace = var.test_namespace
  }

  spec {
    replicas = 1
    selector {
      match_labels = {
        app = "sample-app"
      }
    }
    template {
      metadata {
        labels = {
          app = "sample-app"
        }
        annotations = {
          # these annotations allow for OTel Java instrumentation
          "instrumentation.opentelemetry.io/inject-java" = "true"
        }
      }
      spec {
        service_account_name = var.service_account_aws_access
        container {
          name = "back-end"
          image = var.sample_app_image
          image_pull_policy = "Always"
          env {
            #inject the test id to service name for unique App Signals metrics
            name = "OTEL_SERVICE_NAME"
            value = "sample-application-${var.test_id}"
          }
          port {
            container_port = 8080
          }
        }
      }
    }
  }
}

resource "kubernetes_service" "sample_app_service" {
  depends_on = [ kubernetes_deployment.sample_app_deployment ]

  metadata {
    name = "sample-app-service"
    namespace = var.test_namespace
  }
  spec {
    type = "NodePort"
    selector = {
        app = "sample-app"
    }
    port {
      protocol = "TCP"
      port = 8080
      target_port = 8080
      node_port = 30100
    }
  }
}

resource "kubernetes_ingress_v1" "sample-app-ingress" {
  depends_on = [kubernetes_service.sample_app_service]
  wait_for_load_balancer = true
  metadata {
    name = "sample-app-ingress-${var.test_id}"
    namespace = var.test_namespace
    annotations = {
      "kubernetes.io/ingress.class" = "alb"
      "alb.ingress.kubernetes.io/scheme" = "internet-facing"
      "alb.ingress.kubernetes.io/target-type" = "ip"
    }
    labels = {
        app = "sample-app-ingress"
    }
  }
  spec {
    rule {
      http {
        path {
          path = "/"
          path_type = "Prefix"
          backend {
            service {
              name = kubernetes_service.sample_app_service.metadata[0].name
              port {
                number = 8080
              }
            }
          }
        }
      }
    }
  }
}

# Set up the remote service

resource "kubernetes_deployment" "sample_remote_app_deployment" {

  metadata {
    name      = "sample-r-app-deployment-${var.test_id}"
    namespace = var.test_namespace
    labels    = {
      app = "remote-app"
    }
  }

  spec {
    replicas = 1
    selector {
      match_labels = {
        app = "remote-app"
      }
    }
    template {
      metadata {
        labels = {
          app = "remote-app"
        }
        annotations = {
          # these annotations allow for OTel Java instrumentation
          "instrumentation.opentelemetry.io/inject-java" = "true"
        }
      }
      spec {
        service_account_name = var.service_account_aws_access
        container {
          name = "back-end"
          image = var.sample_remote_app_image
          image_pull_policy = "Always"
          port {
            container_port = 8080
          }
        }
      }
    }
  }
}

resource "kubernetes_service" "sample_remote_app_service" {
  depends_on = [ kubernetes_deployment.sample_remote_app_deployment ]

  metadata {
    name = "sample-remote-app-service"
    namespace = var.test_namespace
  }
  spec {
    type = "NodePort"
    selector = {
      app = "remote-app"
    }
    port {
      protocol = "TCP"
      port = 8080
      target_port = 8080
      node_port = 30101
    }
  }
}

output "sample_app_endpoint" {
  value = kubernetes_ingress_v1.sample-app-ingress.status.0.load_balancer.0.ingress.0.hostname
}
