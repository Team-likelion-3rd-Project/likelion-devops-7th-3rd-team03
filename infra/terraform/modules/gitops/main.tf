data "aws_caller_identity" "current" {}

resource "helm_release" "argocd" {
  name             = "argocd"
  repository       = "https://argoproj.github.io/argo-helm"
  chart            = "argo-cd"
  version          = "10.10.5"
  namespace        = "argocd"
  create_namespace = true

  # ArgoCD는 기본적으로 하위 Application의 상태를 보지 않아, app-of-apps의 sync-wave가
  # 앞 앱이 Healthy가 될 때까지 기다리지 않는다. Application의 health를 그대로 쓰게 한다.
  # (https://argo-cd.readthedocs.io/en/stable/operator-manual/health/#argocd-app)
  values = [yamlencode({
    configs = {
      cm = {
        "resource.customizations.health.argoproj.io_Application" = <<-EOT
          hs = {}
          hs.status = "Progressing"
          hs.message = ""
          if obj.status ~= nil then
            if obj.status.health ~= nil then
              hs.status = obj.status.health.status
              if obj.status.health.message ~= nil then
                hs.message = obj.status.health.message
              end
            end
          end
          return hs
        EOT
      }
    }
  })]
}

# prod 앱 묶음(infra/argocd/prod, Helm 차트)을 가리키는 root Application.
# apply 후 kubectl로 등록하지 않아도 ArgoCD가 prod 앱과 모니터링을 동기화한다.
resource "helm_release" "argocd_apps" {
  name       = "argocd-apps"
  repository = "https://argoproj.github.io/argo-helm"
  chart      = "argocd-apps"
  version    = "2.0.6"
  namespace  = "argocd"

  values = [yamlencode({
    applications = {
      snipy-root = {
        namespace = "argocd"
        project   = "default"
        source = {
          repoURL        = var.argocd_repo_url
          targetRevision = var.argocd_target_revision
          path           = "infra/argocd/prod"
          # 하위 앱들도 같은 브랜치를 보도록 차트에 넘긴다
          helm = {
            parameters = [{ name = "targetRevision", value = var.argocd_target_revision }]
          }
        }
        destination = {
          server    = "https://kubernetes.default.svc"
          namespace = "argocd"
        }
        syncPolicy = {
          automated = { selfHeal = true }
        }
      }
    }
  })]

  # 하위 앱이 ExternalSecret(ESO CRD)을 쓰므로 ESO를 먼저 설치한다
  depends_on = [helm_release.argocd, helm_release.external_secrets]
}

resource "aws_iam_role" "external_secrets" {
  name = "${var.cluster_name}-external-secrets"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Federated = var.oidc_provider_arn
      }
      Action = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${replace(var.oidc_provider, "https://", "")}:sub" = "system:serviceaccount:external-secrets:external-secrets"
          "${replace(var.oidc_provider, "https://", "")}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "external_secrets" {
  name = "${var.cluster_name}-external-secrets-policy"
  role = aws_iam_role.external_secrets.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "secretsmanager:GetSecretValue",
        "secretsmanager:DescribeSecret"
      ]
      # 이 클러스터 이름으로 시작하는 시크릿만 읽을 수 있게 최소권한 범위 지정
      Resource = "arn:aws:secretsmanager:${var.aws_region}:${data.aws_caller_identity.current.account_id}:secret:${var.cluster_name}/*"
    }]
  })
}

resource "helm_release" "external_secrets" {
  name             = "external-secrets"
  repository       = "https://charts.external-secrets.io"
  chart            = "external-secrets"
  version          = "2.13.0"
  namespace        = "external-secrets"
  create_namespace = true

  set {
    name  = "serviceAccount.annotations.eks\\.amazonaws\\.com/role-arn"
    value = aws_iam_role.external_secrets.arn
  }
}
