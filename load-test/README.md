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

# 목표 TPS 지정 (비우면 viral-spike.js 기본값 1600)
load-test/viral-spike-run.sh snipy http://redirect-service:8080 3 800

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

- k6 Job은 redirect 파드가 없는 노드에 우선 배치된다(soft anti-affinity). 노드 CPU를 볼 때는 `kubectl get pods -o wide`로 k6와 redirect 파드가 같은 노드에 있는지 먼저 확인한다. HPA가 나중에 늘린 redirect 파드는 k6 노드에 올 수 있다.

## 정리

- Job/ConfigMap: 다음 실행 시 `run-smoke.sh`가 자동으로 지우고 새로 만듦. 수동 정리는:
  ```bash
  kubectl --context <context> delete job k6-load-test configmap k6-load-test-script
  ```
- 세 스크립트(`normal-traffic-run.sh`, `breakpoint-run.sh`, `viral-spike-run.sh`)가 만든 테스트 링크는 테스트 종료 시 자동으로 soft delete(`is_visible = FALSE`)된다 (`trap cleanup EXIT` — 중간에 실패해도 실행됨, #251). 링크 행과 클릭 로그(`link_daily_stats`, S3/Athena)는 남는다. 실제로 지우려면 아래 "테스트 링크 정리 (dev)".
## 테스트 링크 정리 (dev)

스크립트는 끝날 때 테스트 링크를 soft delete(`is_visible = FALSE`)만 한다 (#251).
링크를 실제로 지우면 S3에 남은 클릭이 집계 외래키에 걸리기 때문이다. 그래서 실행할 때마다 링크 행이 남는다 (normal 30만 행씩).

dev에서 쌓인 테스트 링크를 정말 지우려면, **그날 배치가 끝난 뒤** 통계 행부터 지우고 링크를 지운다.
링크를 먼저 지우면 통계 행이 외래키에 걸려 지워지지 않는다.

```sql
-- prefix: 스크립트가 출력한 값 (예: lt123456, bp123456, vs12345678)
DELETE s FROM link_daily_dimension_stats s JOIN links l ON l.id = s.link_id
 WHERE l.slug LIKE 'lt123456%' AND l.is_visible = FALSE;
DELETE s FROM link_daily_stats s JOIN links l ON l.id = s.link_id
 WHERE l.slug LIKE 'lt123456%' AND l.is_visible = FALSE;
DELETE FROM links WHERE slug LIKE 'lt123456%' AND is_visible = FALSE;
```

- 30만 행은 한 번에 지우면 테이블 잠금이 길어진다. 사용이 적은 시간에 하거나 `LIMIT 10000`을 붙여 여러 번 나눠 지운다.
- 지운 뒤에도 S3에 클릭이 남아 있어, 그 날짜를 다시 집계하면 해당 행은 건너뛴다 (#253, "links 에 없는 링크의 행 N건을 건너뜁니다" 로그).
- prod(부하 테스트 환경)는 끝나면 destroy 하므로 정리하지 않아도 된다.