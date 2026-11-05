# 환경별 배포 구조 (한 base + overlay·component)

온프렘(dev)과 AWS(prod)는 **같은 `base`를 쓰고**, 환경마다 다른 부분만 `overlays/`의 patch와 리소스, 그리고 `components/`로 얹는다.
앱 매니페스트를 환경마다 따로 복사해 두지 않는다. "한 곳에서 정의하고 환경별로 값을 덧씌운다"가 이 구조의 원칙이다.

## 구조

```
infra/k8s/
├── base/                  환경 공통 리소스 (두 환경이 똑같이 가져간다)
├── components/
│   ├── aws/               AWS(EKS)에서만 동작하는 리소스 묶음
│   └── monitoring/        Prometheus Operator용 ServiceMonitor 묶음
└── overlays/
    ├── dev/               온프렘 VM 클러스터 (kubeadm)
    └── prod/              AWS EKS
```

## 어떻게 배포되나

| | 온프렘 (dev) | AWS (prod) |
|---|---|---|
| Argo CD 앱 | `infra/onprem/argocd/snipy-dev-app.yaml` | `infra/argocd/prod` (앱을 묶는 Helm 차트) 안의 `snipy-prod` |
| 가리키는 경로 | `infra/k8s/overlays/dev` | `infra/k8s/overlays/prod` |
| 브랜치 | `develop` | `main` (릴리스 PR 머지 시) |
| 이미지 | GHCR, CI가 같은 커밋 SHA로 태그 갱신 | dev와 같은 GHCR 이미지, 같은 SHA |

- prod의 `infra/argocd/prod`는 Argo CD 앱들을 묶는 app-of-apps 용도의 Helm 차트다. 애플리케이션 매니페스트 자체는 Helm이 아니라 위의 kustomize overlay다.
- 이미지 태그는 overlay의 `images:`에만 기록한다. CI가 두 overlay의 태그를 같은 SHA로 함께 갱신한다.

## 각 계층이 담는 것

### base (공통)
management-service와 redirect-service의 Deployment·Service, redirect-service의 ServiceAccount, HPA, Grafana 대시보드 ConfigMap.

### components/aws
AWS 전용. 온프렘에는 넣지 않는다.
- ALB Ingress (origin 검증 헤더 포함)
- External Secrets `ClusterSecretStore` (Secrets Manager)
- gp3 StorageClass (EBS CSI)
- Athena 일별 집계 배치 (CronJob, ServiceAccount, ConfigMap)

### components/monitoring
앱 ServiceMonitor 2개(management, redirect). Prometheus Operator가 있는 환경에서만 포함한다. dev는 포함하지 않는다.

## 환경별 차이

| 영역 | 온프렘 (overlays/dev) | AWS (overlays/prod + components) |
|---|---|---|
| DB | 클러스터 안 MySQL (`mysql.yaml`) | RDS (클러스터 밖) |
| Redis | 클러스터 안 (`redis.yaml`) | ElastiCache (클러스터 밖) |
| 시크릿 | SealedSecret (`infra/onprem/secrets`) | ExternalSecret (`external-secrets.yaml`) |
| 스토리지 | local-path | gp3 (EBS) |
| 프론트 | Nginx Deployment + PDB (클러스터 안) | S3 + CloudFront (클러스터에 없음, Terraform) |
| 로그·통계 | Fluent Bit + SeaweedFS + 집계 CronJob | Firehose + Athena |
| 배포 방식 | Argo Rollouts 카나리 (`*-rollout.yaml`) | 롤링 업데이트 |
| 모니터링 | 포함하지 않음 | `components/monitoring` + kube-prometheus-stack(별도 Argo 앱) |
| Ingress | ingress-nginx (NodePort) | ALB |

## 왜 Helm 차트 하나로 합치지 않았나

2026-11-03 클라우드 담당자와 논의해 정했다. 위 표의 차이가 너무 커서 하나의 차트로 합치면 `values`로 켜고 끄는 분기가 많아진다.
이미 두 환경이 같은 base를 공유하고 차이가 component와 overlay로 나뉘어 있어서, "한 곳에서 정의하고 환경별로 덧씌운다"는 목적은 이 구조로 충족한다.
(마일스톤 M5의 "한 차트로 두 환경 배포"는 "한 base에 overlay·component로 두 환경 배포"로 해석한다.)

다시 검토할 조건: 환경이 3개 이상으로 늘어나거나, 차이가 크게 줄어 `values`만으로 표현 가능해질 때.

## 변경할 때 규칙

1. **두 환경이 똑같이 필요한 것** → `base`
2. **AWS에서만 필요한 것** → `components/aws`
3. **한 환경의 값만 다른 것** → 해당 overlay의 patch
4. **한 환경에만 있는 리소스** → 해당 overlay의 resources
5. 이미지 태그는 overlay의 `images:`에만 쓴다 (손으로 고치지 않는다).

## 렌더 확인

`infra/k8s/`를 바꾼 PR은 CI(`K8s Render`, `.github/workflows/k8s-render.yml`)가 두 환경 overlay를 모두 렌더해 확인한다.
렌더가 깨지면 Argo CD가 sync하지 못하므로, 머지 전에 이 검사가 통과해야 한다.

로컬에서 먼저 보려면 같은 명령을 쓴다.

```bash
kubectl kustomize infra/k8s/overlays/dev > /dev/null && echo dev OK
kubectl kustomize infra/k8s/overlays/prod > /dev/null && echo prod OK
```
