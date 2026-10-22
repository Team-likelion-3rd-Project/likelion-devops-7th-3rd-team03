module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.0"

  cluster_name    = var.cluster_name
  cluster_version = var.cluster_version

  vpc_id     = var.vpc_id
  subnet_ids = var.subnet_ids # 노드는 프라이빗 서브넷에 배치

  cluster_endpoint_public_access = true # access_entries 로 권한 관리
  # apply를 실행한 주체(사람 또는 CI 역할)에게 관리자 권한을 준다. 같은 apply 안에서
  # helm/kubernetes provider가 클러스터에 접근해야 하므로 꼭 필요하다.
  # 실행 주체를 cluster_admin_arns에 또 넣으면 access entry가 중복돼 실패한다.
  enable_cluster_creator_admin_permissions = true

  access_entries = {
    for arn in var.cluster_admin_arns :
    split("/", arn)[1] => {
      principal_arn = arn
      policy_associations = {
        admin = {
          policy_arn   = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy"
          access_scope = { type = "cluster" }
        }
      }
    }
  }

  kms_key_administrators = var.cluster_admin_arns

  # ── 필수 애드온 ─────────────────────────────
  cluster_addons = {
    vpc-cni = {
      most_recent = true
    }
    coredns = {
      most_recent = true
    }
    kube-proxy = {
      most_recent = true
    }
  }

  # ── Managed Node Group ──────────────────────
  eks_managed_node_groups = {
    snipy_nodes = {
      instance_types = [var.node_instance_type]
      capacity_type  = "ON_DEMAND"

      min_size     = var.node_min_size
      max_size     = var.node_max_size
      desired_size = var.node_desired_size

      subnet_ids = var.subnet_ids

      labels = {
        role = "app"
      }

      tags = {
        "k8s.io/cluster-autoscaler/enabled"             = "true"
        "k8s.io/cluster-autoscaler/${var.cluster_name}" = "owned"
      }
    }
  }

  tags = {
    Project = "snipy"
  }
}

# ── Metrics Server (redirect-service HPA에 필수) ──
resource "helm_release" "metrics_server" {
  name       = "metrics-server"
  repository = "https://kubernetes-sigs.github.io/metrics-server/"
  chart      = "metrics-server"
  version    = "3.14.0"
  namespace  = "kube-system"

  depends_on = [module.eks]
}

# ── Cluster Autoscaler용 IRSA ──────────────────
# Helm 배포만으로는 노드가 스케일되지 않음 — ASG 조작 권한(IAM)이 파드에 IRSA로 연결돼야 함
module "irsa_cluster_autoscaler" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version = "~> 5.39"

  role_name = "${var.cluster_name}-cluster-autoscaler"

  attach_cluster_autoscaler_policy = true
  cluster_autoscaler_cluster_names = [var.cluster_name]

  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["kube-system:cluster-autoscaler"]
    }
  }
}

# ── Cluster Autoscaler (Helm) ─────────────────
# 이미지 태그를 cluster_version에 맞춰 고정한다. 버전을 안 박으면 차트가
# 항상 최신 CA를 끌어오는데, 2차 때 최신 CA(1.35+)가 EKS 1.31에 없는 DRA 리소스
# (ResourceClaim/ResourceSlice/DeviceClass)를 watch하려다 계속 실패해서
# informer sync가 안 끝나고 스케일업 로직 자체가 안 도는 문제가 있었다.
resource "helm_release" "cluster_autoscaler" {
  name       = "cluster-autoscaler"
  repository = "https://kubernetes.github.io/autoscaler"
  chart      = "cluster-autoscaler"
  version    = "9.59.0"
  namespace  = "kube-system"

  set {
    name  = "image.tag"
    value = "v${var.cluster_version}.0"
  }

  set {
    name  = "autoDiscovery.clusterName"
    value = var.cluster_name
  }

  set {
    name  = "awsRegion"
    value = var.aws_region
  }

  set {
    name  = "rbac.serviceAccount.name"
    value = "cluster-autoscaler"
  }

  set {
    name  = "rbac.serviceAccount.annotations.eks\\.amazonaws\\.com/role-arn"
    value = module.irsa_cluster_autoscaler.iam_role_arn
  }

  depends_on = [module.eks, module.irsa_cluster_autoscaler]
}

# ══════════════════════════════════════════════════
# AWS Load Balancer Controller
# ══════════════════════════════════════════════════
# infra/k8s의 Ingress가 쓰는 `kubernetes.io/ingress.class: alb`와
# alb.ingress.kubernetes.io/* 어노테이션은 이 컨트롤러가 있어야 ALB를 생성함.
# 컨트롤러 없이는 Ingress를 배포해도 아무 일도 일어나지 않음.
module "irsa_aws_load_balancer_controller" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version = "~> 5.39"

  role_name = "${var.cluster_name}-aws-load-balancer-controller"

  attach_load_balancer_controller_policy = true

  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["kube-system:aws-load-balancer-controller"]
    }
  }
}

resource "helm_release" "aws_load_balancer_controller" {
  name       = "aws-load-balancer-controller"
  repository = "https://aws.github.io/eks-charts"
  chart      = "aws-load-balancer-controller"
  version    = "3.6.1"
  namespace  = "kube-system"

  set {
    name  = "clusterName"
    value = var.cluster_name
  }

  set {
    name  = "region"
    value = var.aws_region
  }

  set {
    name  = "vpcId"
    value = var.vpc_id
  }

  set {
    name  = "serviceAccount.name"
    value = "aws-load-balancer-controller"
  }

  set {
    name  = "serviceAccount.annotations.eks\\.amazonaws\\.com/role-arn"
    value = module.irsa_aws_load_balancer_controller.iam_role_arn
  }

  depends_on = [module.eks, module.irsa_aws_load_balancer_controller]
}

# ══════════════════════════════════════════════════
# external-dns
# ══════════════════════════════════════════════════
# (external-dns v0.22부터 접두사가 external-dns.alpha.kubernetes.io/에서 바뀜. 옛 접두사는 읽지 않는다)
# Ingress의 external-dns.kubernetes.io/hostname 어노테이션(alb.<도메인>)을 보고
# Route53에 ALB를 가리키는 레코드를 만든다. CloudFront 오리진이 이 이름을 쓴다(modules/edge).
module "irsa_external_dns" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version = "~> 5.39"

  role_name = "${var.cluster_name}-external-dns"

  attach_external_dns_policy    = true
  external_dns_hosted_zone_arns = ["arn:aws:route53:::hostedzone/${var.route53_zone_id}"]

  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["kube-system:external-dns"]
    }
  }
}

resource "helm_release" "external_dns" {
  name       = "external-dns"
  repository = "https://kubernetes-sigs.github.io/external-dns/"
  chart      = "external-dns"
  version    = "1.23.0"
  namespace  = "kube-system"

  values = [yamlencode({
    provider   = { name = "aws" }
    sources    = ["ingress"]
    policy     = "sync" # Ingress를 지우면 레코드도 지운다 (destroy 전 정리)
    txtOwnerId = var.cluster_name
    # 이 도메인 아래, 어노테이션에 적은 이름만 만든다. Ingress rules의 host(<도메인>)는
    # CloudFront를 가리키는 terraform 레코드라 건드리지 않게 무시한다.
    domainFilters = [var.domain_name]
    extraArgs     = ["--ignore-ingress-rules-spec"]
    serviceAccount = {
      name = "external-dns"
      annotations = {
        "eks.amazonaws.com/role-arn" = module.irsa_external_dns.iam_role_arn
      }
    }
  })]

  depends_on = [module.eks, module.irsa_external_dns]
}

# ══════════════════════════════════════════════════
# EBS CSI Driver
# ══════════════════════════════════════════════════
# Prometheus/Grafana(kube-prometheus-stack)를 EBS 기반 PVC로 영속화하기 위해 필요.
# cluster_addons 안에 바로 못 넣는 이유: service_account_role_arn이
# module.eks.oidc_provider_arn을 참조하는 IRSA 리소스에서 나오는데, 그걸
# module "eks" 입력값(cluster_addons)에 넣으면 모듈이 자기 자신의 출력을
# 입력으로 참조하는 순환 의존성이 생김. 그래서 별도 aws_eks_addon 리소스로 분리.
module "irsa_ebs_csi_driver" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version = "~> 5.39"

  role_name = "${var.cluster_name}-ebs-csi-driver"

  attach_ebs_csi_policy = true

  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["kube-system:ebs-csi-controller-sa"]
    }
  }
}

resource "aws_eks_addon" "ebs_csi_driver" {
  cluster_name             = module.eks.cluster_name
  addon_name               = "aws-ebs-csi-driver"
  service_account_role_arn = module.irsa_ebs_csi_driver.iam_role_arn

  depends_on = [module.eks, module.irsa_ebs_csi_driver]
}
