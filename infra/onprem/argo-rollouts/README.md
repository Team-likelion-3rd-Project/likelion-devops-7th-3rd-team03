# 온프렘 Argo Rollouts (redirect 카나리)

redirect-service 를 롤링 업데이트 대신 카나리로 배포한다.
새 버전을 파드 일부에만 올리고 k6 로 판단해, 통과하면 자동 승격하고 실패하면 자동 롤백한다.

- `cp-argo-rollouts.sh`: Argo Rollouts 컨트롤러와 kubectl 플러그인 설치 (cp1 에서 실행)
- `infra/k8s/overlays/dev/redirect-rollout.yaml`: Rollout(workloadRef)과 카나리·stable 전용 Service
- `infra/k8s/overlays/dev/redirect-canary-analysis.yaml`: k6 스크립트와 판단 기준(AnalysisTemplate)

## 1. 설치

호스트(저장소 루트)에서 cp1로 복사하고 실행한다.

```bash
scp -r infra/onprem/argo-rollouts k8s@192.168.122.11:~/
```

```bash
bash /home/k8s/argo-rollouts/cp-argo-rollouts.sh
```

컨트롤러 파드 하나(`argo-rollouts`)가 뜨고, `kubectl argo rollouts version`이 버전을 출력하면 된다.
설치 버전은 `v1.8.3`으로 고정한다. 바꿀 때는 `ARGO_ROLLOUTS_VERSION=vX.Y.Z bash cp-argo-rollouts.sh`.

컨트롤러를 먼저 설치한 뒤에 dev overlay 를 동기화해야 한다. 설치 전에는 `Rollout`, `AnalysisTemplate` 리소스 종류가 없어 Argo CD 동기화가 실패한다.

## 2. 동작

```
파드 템플릿이 바뀜 (새 이미지, env 변경 등)
→ 새 버전 파드를 50% 로 (파드 2개 중 1개)
→ k6 가 카나리 파드에만 2분간 요청 (AnalysisRun)
   통과 → 100% 로 승격
   실패 → 자동 abort, 이전 버전으로 복귀 (Rollout 은 Degraded 로 표시)
```

- **workloadRef**: base Deployment(AWS 공용)를 고치지 않고 Rollout 이 그 파드 템플릿을 넘겨받는다. Rollout 이 정상으로 뜨면 Deployment 는 0개로 줄인다(`scaleDown: onsuccess`). Deployment 의 replicas 는 `snipy-dev` Application 의 `ignoreDifferences`로 Argo CD 가 되돌리지 않는다.
- **파드 수 비율**: redirect 는 Ingress 없이 NodePort(30081)로 받아서 트래픽 비율 장치가 없다. 카나리 비율은 파드 수로 정해진다. 그래서 파드가 2개 이상이어야 하고(HPA 최소 2), 단계는 50% 부터다.
- **HPA**: redirect HPA 는 Deployment 가 아니라 Rollout 을 대상으로 한다 (`patch-hpa.yaml`). Deployment 를 보면 Rollout 과 파드 수를 서로 뺏는다.
- **카나리 전용 Service**: Argo Rollouts 가 `redirect-service-canary`·`redirect-service-stable`의 selector 에 버전 해시를 붙여 각각 카나리·stable 파드만 가리키게 만든다. k6 는 카나리 Service 로만 요청해 새 버전만 판단한다.
- 첫 배포(Rollout 이 처음 생길 때)는 비교할 이전 버전이 없어 카나리 없이 바로 100% 가 된다.

## 3. 판단 기준

| 항목 | 값 | 이유 |
|---|---|---|
| 요청 대상 | 없는 slug `/canary-check` (정상이면 404) | 테스트 링크 없는 클러스터에서도 돈다. 리다이렉트가 아니라 클릭 로그·통계를 더럽히지 않는다 |
| 정상 응답 | 302, 404 | 5xx 만 실패로 센다. 302 를 따라 외부로 나가지 않게 `redirects: 0` |
| 부하 | 초당 10건, 2분 (`constant-arrival-rate`) | 워커가 작아 부하 때문에 실패로 판정되지 않게 고정 |
| 에러율 | 1% 미만 | |
| p95 | 150ms 미만 | 팀 부하 테스트 SLO |
| 실패 허용 | 0 (한 번이라도 넘으면 롤백) | |

확인 결과 (2026-10-29, 정상 배포): 1,187건(초당 9.9), 에러 0.00%, p95 27.87ms → 자동 승격.

## 4. 보기와 수동 조작

```bash
kubectl argo rollouts -n snipy get rollout redirect-service --watch   # 단계·파드·AnalysisRun
kubectl -n snipy get analysisrun                                     # 판단 결과
kubectl -n snipy logs job/<k6 Job 이름> | grep -E "✓|✗"              # 판단 근거
```

| 하려는 것 | 명령 |
|---|---|
| 지금 단계 건너뛰고 다음으로 | `kubectl argo rollouts -n snipy promote redirect-service` |
| 바로 이전 버전으로 되돌리기 | `kubectl argo rollouts -n snipy abort redirect-service` |
| abort 후 다시 시도 | `kubectl argo rollouts -n snipy retry rollout redirect-service` |

abort 하면 Rollout 이 `Degraded`가 되고, Argo CD 알림(`on-health-degraded`)이 Slack 으로 간다.

## 5. 시험

### 정상 배포 → 자동 승격

파드 템플릿이 바뀌면 카나리가 시작된다. Deployment 에 아무 env 나 넣는다.

```bash
kubectl -n snipy set env deploy/redirect-service CANARY_TEST=1
kubectl argo rollouts -n snipy get rollout redirect-service --watch
```

50% → AnalysisRun Successful → 100% 면 된다 (k6 2분 포함 3~4분).

### 에러 버전 → 자동 롤백

장애 주입 필터(#182)를 카나리 파드에서만 켠다. 요청의 50% 가 500 이 된다.

```bash
kubectl -n snipy set env deploy/redirect-service APP_FAULT_INJECTION_ENABLED=true APP_FAULT_INJECTION_ERROR_RATE=0.5
kubectl argo rollouts -n snipy get rollout redirect-service --watch
```

50% → AnalysisRun Failed → 자동 abort → 이전 버전 2개로 복귀하면 된다. 시험 후 env 를 빼고 `retry` 한다.

```bash
kubectl -n snipy set env deploy/redirect-service APP_FAULT_INJECTION_ENABLED- APP_FAULT_INJECTION_ERROR_RATE- CANARY_TEST-
kubectl argo rollouts -n snipy retry rollout redirect-service
```

손으로 넣은 env 는 selfHeal 이 꺼져 있어 Argo CD 가 되돌리지 않는다. 시험 후 반드시 뺀다.

## 6. 범위

| 서비스 | 카나리 | 이유 |
|---|---|---|
| redirect | 적용 | 모든 클릭이 지나가는 핵심 경로. 판단이 단순(302·404 정상, 5xx 실패) |
| management | 미적용 (후속) | ingress-nginx 가중치 설정과, 로그인 없이 확인할 엔드포인트가 필요 |
| frontend | 적용하지 않음 | 정적 파일이라 버전이 섞이면 새 HTML 이 옛 파드에 없는 JS 를 요청해 화면이 깨진다. 문제는 빌드 단계(번들 검증, Trivy)에서 거른다 |

prod(AWS)는 롤링 업데이트 그대로다. 이 설정은 dev overlay 에만 있다.

## 7. 문제 해결

| 증상 | 원인 | 조치 |
|---|---|---|
| Argo CD 동기화가 `the server could not find the requested resource (Rollout)` | 컨트롤러 미설치 | 1번 설치 후 다시 동기화 |
| 카나리 파드가 50% 에서 안 뜸 (`Pending`) | 워커 자원 부족 | `kubectl -n snipy describe pod` 이벤트 확인, 다른 작업 줄이기 |
| AnalysisRun 이 계속 Running | k6 이미지 pull 실패 (`grafana/k6`) | VMware NAT 재시작 (Argo CD README 문제 해결) |
| 정상 버전인데 AnalysisRun Failed | `/canary-check`가 404 가 아닌 응답 | `curl http://<워커IP>:30081/canary-check` 로 확인 |
| Deployment 와 Rollout 파드가 같이 늘어남 | HPA 가 Deployment 를 보고 있음 | `patch-hpa.yaml`의 `scaleTargetRef`가 Rollout 인지 확인 |

## 8. 되돌리기 (롤링 업데이트로)

dev overlay 에서 `redirect-rollout.yaml`, `redirect-canary-analysis.yaml`을 빼고 `patch-hpa.yaml`의 redirect `scaleTargetRef`를 Deployment 로 돌린다.
Rollout 을 지우기 전에 Deployment 를 먼저 늘려야 서비스가 끊기지 않는다.

```bash
kubectl -n snipy scale deploy redirect-service --replicas=2
kubectl -n snipy rollout status deploy/redirect-service
kubectl -n snipy delete rollout redirect-service
```