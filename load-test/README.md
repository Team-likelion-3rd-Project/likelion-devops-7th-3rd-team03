# load-test

EKS 클러스터 안에서 k6로 부하테스트를 1회성으로 실행하기 위한 도구. GitOps(ArgoCD) 관리 대상이 아니고, 필요할 때 로컬에서 직접 실행한다.

## 사전 준비

1. `infra/argocd/{dev,prod}/observability.yaml`이 배포되어 `monitoring` 네임스페이스에 kube-prometheus-stack(Prometheus/Grafana)이 떠 있어야 함.
2. Grafana admin 비밀번호는 `modules/app-secrets`가 자동 생성 → ESO가 시크릿으로 반영. 조회:
   ```bash
   aws secretsmanager get-secret-value \
     --secret-id snipy-dev-cluster/app/grafana-admin-password \
     --query SecretString --output text
   # prod는 snipy-cluster/app/grafana-admin-password
   ```
3. `envsubst` 필요 (macOS: `brew install gettext`).
4. **base-url은 공개 도메인 말고 클러스터 내부 Service DNS(`http://redirect-service:8080`)로.** WAF의 `waf_allowed_countries=["KR"]` 때문에 클러스터(싱가포르 리전) 안에서 공개 도메인을 치면 CloudFront가 403으로 막는다 (직접 겪음).

## 실행

```bash
load-test/run-smoke.sh <kubectl-context> <script-file> <base-url> <slug> [vus] [duration]

# 간단한 smoke test (vus/duration 기반) — slug는 management-service API로 미리 만들어둔 값
load-test/run-smoke.sh snipy redirect-smoke.js http://redirect-service:8080 test 10 30s

# 바이럴 스파이크 시나리오 — 콜드 링크 생성부터 실행/정리까지 원스톱 (base-url 기본값이 이미 내부 DNS)
load-test/viral-spike-run.sh snipy

# Load/Stress (PEAK_TPS만 다름) — 링크 30만 개 생성부터 실행/정리까지 원스톱
load-test/normal-traffic-run.sh snipy

# Breakpoint — 단일 파드 처리량 한계 측정. Service가 아니라 파드 하나의 Pod IP로 직접 쏨
# (여러 파드로 분산되면 "파드 1개 한계"를 잴 수 없어서. HPA/Deployment는 안 건드림)
load-test/breakpoint-run.sh snipy
```

`viral-spike.js`/`normal-traffic.js`는 management-service API로 못 만드는 링크(캐시 즉시 채워짐, slug 자동생성)가 필요해서, `*-run.sh`가 MySQL에 직접 INSERT하고 테스트 후 정리한다 (자세한 내용은 `SCENARIOS.md`).

## 결과 확인

k6가 `--out experimental-prometheus-rw`로 결과를 클러스터 내 Prometheus에 바로 remote-write한다. Grafana에서 보려면 공식 k6 대시보드(grafana.com dashboard id **19665**)를 Import 메뉴에서 한 번 가져오면 된다.

모든 실행마다 유니크한 `testid`가 자동 부여되고(`run-smoke.sh`가 시작 시 stdout에 출력, k6 로그에도 `[testid] ...`로 찍힘) 모든 메트릭에 라벨로 붙는다. Prometheus/Grafana에서 `testid="<값>"`으로 다른 실행과 섞이지 않게 필터링 가능.

```bash
kubectl --context <context> port-forward -n monitoring svc/kube-prometheus-grafana 3000:80
# http://localhost:3000, admin / (사전 준비 2번에서 조회한 비밀번호)
```

## 정리

- Job/ConfigMap: 다음 실행 시 `run-smoke.sh`가 자동으로 지우고 새로 만듦. 수동 정리는:
  ```bash
  kubectl --context <context> delete job k6-load-test configmap k6-load-test-script
  ```
- `viral-spike-run.sh`로 만든 콜드 링크는 테스트 종료 시 자동 DELETE됨 (`trap cleanup EXIT` — 중간에 실패해도 실행됨). 클릭 로그(`link_daily_stats`, S3/Athena)는 자동 정리되지 않음.
