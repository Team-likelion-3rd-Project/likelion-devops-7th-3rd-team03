# 온프렘 Argo Rollouts (redirect·management 카나리)

redirect-service 와 management-service 를 롤링 업데이트 대신 카나리로 배포한다.
새 버전을 파드 일부에만 올리고 k6 로 판단해, 통과하면 자동 승격하고 실패하면 자동 롤백한다.

- `cp-argo-rollouts.sh`: Argo Rollouts 컨트롤러와 kubectl 플러그인 설치 (cp1 에서 실행)
- `infra/k8s/overlays/dev/redirect-rollout.yaml`, `redirect-canary-analysis.yaml`: redirect 카나리 (Rollout·전용 Service, k6 판단 기준)
- `infra/k8s/overlays/dev/management-rollout.yaml`, `management-canary-analysis.yaml`: management 카나리 (redirect 와 같은 방식)

카나리 설정 파일은 dev overlay 에 있어 Argo CD 가 동기화로 적용한다. 사람이 하는 건 컨트롤러 설치(1번) 한 번뿐이다.

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
→ 1분 대기 (JVM 예열)
→ k6 가 카나리 파드에만 2분간 요청 (AnalysisRun)
   통과 → 100% 로 승격
   실패 → 자동 abort, 이전 버전으로 복귀 (Rollout 은 Degraded 로 표시)
```

- **workloadRef**: base Deployment(AWS 공용)를 고치지 않고 Rollout 이 그 파드 템플릿을 넘겨받는다. Rollout 이 정상으로 뜨면 Deployment 는 0개로 줄인다(`scaleDown: onsuccess`). Deployment 의 replicas 는 `snipy-dev` Application 의 `ignoreDifferences`로 Argo CD 가 되돌리지 않는다.
- **파드 수 비율**: 트래픽 비율 장치(Ingress 가중치)를 쓰지 않고 파드 수로 나눈다. 그래서 파드가 2개 이상이어야 하고(HPA 최소 2), 단계는 50% 부터다.
- **HPA**: 두 서비스의 HPA 는 Deployment 가 아니라 Rollout 을 대상으로 한다 (`patch-hpa.yaml`). Deployment 를 보면 Rollout 과 파드 수를 서로 뺏는다. HPA 는 Rollout(wave 2) 다음인 wave 3 에 만든다 (#224).
- **카나리 전용 Service**: Argo Rollouts 가 `<서비스>-canary`·`<서비스>-stable`의 selector 에 버전 해시를 붙여 각각 카나리·stable 파드만 가리키게 만든다. k6 는 카나리 Service 로만 요청해 새 버전만 판단한다.
- **1분 예열 대기**: 새 파드는 JVM 예열 때문에 처음 수십 초가 느리다. 대기 없이 판단했을 때 정상 버전이 p95 2.84초로 떨어져 롤백된 적이 있다.
- 첫 배포(Rollout 이 처음 생길 때)는 비교할 이전 버전이 없어 카나리 없이 바로 100% 가 된다.
- management 이미지를 쓰는 클릭 집계 배치(CronJob)는 Rollout 과 무관하게 따로 돈다.

## 3. 판단 기준

| 항목 | redirect | management |
|---|---|---|
| 요청 대상 | 없는 slug `/canary-check` | 로그인 없이 `GET /api/workspaces` (내 워크스페이스 목록) |
| 정상 응답 | 302, 404 (`redirects: 0`) | 401 |
| 실패 | 5xx | 5xx |
| 이유 | 테스트 링크 없이 돌고, 클릭 로그·통계를 남기지 않음 | 보안 필터까지 처리했는지 확인. 데이터를 바꾸지 않고 테스트 계정이 필요 없음 |

| 공통 | 값 | 이유 |
|---|---|---|
| 부하 | 초당 10건, 2분 (`constant-arrival-rate`) | 워커가 작아 부하 때문에 실패로 판정되지 않게 고정 |
| 에러율 | 1% 미만 | |
| p95 | 150ms 미만 | 팀 부하 테스트 SLO |
| 실패 허용 | 0 (한 번이라도 넘으면 롤백) | |

확인 결과 (온프렘 dev):

| 서비스 | 시험 | k6 | 결과 |
|---|---|---|---|
| redirect | 정상 배포 (env 변경) | 1,187건, 에러 0.00%, p95 27.87ms | 자동 승격 |
| redirect | 새 이미지, 예열 대기 없음 | 에러 0.00%, p95 2.84s | 자동 롤백 (예열 중 고부하가 겹침) |
| redirect | 새 이미지, 예열 1분 대기 | 통과 | 자동 승격 |
| redirect | 장애 주입 50% | 1,190건, 에러 48.31%, p95 41.14ms | 자동 롤백 |
| redirect | 머지 후 자동 배포 (#196) | 통과 | 사람 개입 없이 자동 승격 |
| management | 머지 후 자동 배포 (#218, redirect 와 동시) | 1,201건, 에러 0.00%, p95 7.87ms | 자동 승격 |
| management | 장애 주입 50% | 1,201건, 에러 49.62%, p95 11.5ms | 자동 롤백 (장애 응답을 직접 써서 401 로 바뀌지 않음) |

롤백된 리비전은 Rollout 기록에 `AnalysisRun ✖ Failed`로 남고, 서비스는 이전 stable 파드가 계속 맡는다.
장애 주입을 빼면 파드 템플릿이 이전과 같아져 같은 ReplicaSet 으로 바로 돌아온다 (카나리 없음).

## 4. 보기와 수동 조작

카나리 단계를 화면으로 보려면 Argo Rollouts 대시보드를 띄운다.

```bash
nohup kubectl argo rollouts dashboard --port 3100 --address 0.0.0.0 >/tmp/rollouts-dash.log 2>&1 &
```

브라우저에서 `http://<cp1 IP>:3100` → 네임스페이스 `snipy` → Rollout 선택. 끌 때는 `pkill -f "rollouts dashboard"`.

아래는 redirect 기준이다. management 는 `redirect-service`를 `management-service`로 바꾼다.

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

management 는 `deploy/redirect-service`, `rollout redirect-service`를 `management-service`로 바꾼다.

### 정상 배포 → 자동 승격

파드 템플릿이 바뀌면 카나리가 시작된다. Deployment 에 아무 env 나 넣는다.

```bash
kubectl -n snipy set env deploy/redirect-service CANARY_TEST=1
kubectl argo rollouts -n snipy get rollout redirect-service --watch
```

50% → 1분 대기 → AnalysisRun Successful → 100% 면 된다 (4~5분).

### 에러 버전 → 자동 롤백

장애 주입 필터를 카나리 파드에서만 켠다. 요청의 50% 가 500 이 된다 (redirect #182, management #222).

```bash
kubectl -n snipy set env deploy/redirect-service APP_FAULT_INJECTION_ENABLED=true APP_FAULT_INJECTION_ERROR_RATE=0.5
kubectl argo rollouts -n snipy get rollout redirect-service --watch
```

50% → 1분 대기 → AnalysisRun Failed → 자동 abort → 이전 버전 2개로 복귀하면 된다. **판단이 끝날 때까지(약 5분) 건드리지 않는다.**

중간에 실제로 500 이 섞이는지 보려면 Service 를 거치는 주소로 부른다 (port-forward 는 파드 하나에만 붙어 카나리 응답이 안 보일 수 있다).

```bash
for i in $(seq 20); do curl -s -o /dev/null -w "%{http_code}\n" http://<워커IP>:30081/canary-check; done | sort | uniq -c        # redirect: 404 + 500
for i in $(seq 20); do curl -s -o /dev/null -w "%{http_code}\n" http://<워커IP>:30080/api/workspaces; done | sort | uniq -c     # management: 401 + 500
```

시험 후 env 를 빼고 `retry` 한다.

```bash
kubectl -n snipy set env deploy/redirect-service APP_FAULT_INJECTION_ENABLED- APP_FAULT_INJECTION_ERROR_RATE- CANARY_TEST-
kubectl argo rollouts -n snipy retry rollout redirect-service
```

손으로 넣은 env 는 selfHeal 이 꺼져 있어 Argo CD 가 되돌리지 않는다. 시험 후 반드시 뺀다.

## 6. 범위

| 서비스 | 카나리 | 비고 |
|---|---|---|
| redirect | 적용 | 모든 클릭이 지나가는 핵심 경로 |
| management | 적용 | |
| frontend | 적용하지 않음 | 정적 파일이라 버전이 섞이면 새 HTML 이 옛 파드에 없는 JS 를 요청해 화면이 깨진다. 문제는 빌드 단계(번들 검증, Trivy)에서 거른다 |

prod(AWS)는 롤링 업데이트 그대로다. 이 설정은 dev overlay 에만 있다.

## 7. 문제 해결

| 증상 | 원인 | 조치 |
|---|---|---|
| Argo CD 동기화가 `the server could not find the requested resource (Rollout)` | 컨트롤러 미설치 | 1번 설치 후 다시 동기화 |
| 카나리 파드가 50% 에서 안 뜸 (`Pending`) | 워커 자원 부족 | `kubectl -n snipy describe pod` 이벤트 확인, 다른 작업 줄이기 |
| AnalysisRun 이 계속 Running | k6 이미지 pull 실패 (`grafana/k6`) | VMware NAT 재시작 (Argo CD README 문제 해결) |
| 정상 버전인데 AnalysisRun Failed | 판단 대상이 기대 응답이 아님 | redirect: `curl http://<워커IP>:30081/canary-check` → 404, management: 로그인 없이 `curl http://<워커IP>:30080/api/workspaces` → 401 인지 확인 |
| 정상 버전인데 p95 로 Failed | 예열 중이거나 다른 부하 측정과 겹침 | 측정 중이면 끝난 뒤 `retry rollout` |
| 장애 주입 중인데 Argo CD 는 Healthy | Argo CD 는 파드 Ready·리소스 상태만 본다. 장애 주입은 probe(`/actuator`)를 통과시켜 파드는 Ready 다 | 정상 동작. 요청 실패는 카나리 k6 가 판단한다. 배포 후 평소 에러는 Prometheus 5xx 알림이 필요하다 (미구현) |
| Deployment 와 Rollout 파드가 같이 늘어남 | HPA 가 Deployment 를 보고 있음 | `patch-hpa.yaml`의 `scaleTargetRef`가 Rollout 인지 확인 |
| 카나리가 옛 이미지로 진행됨 | 오래된 overlay 로 `kubectl apply -k` 해서 Deployment 이미지 태그가 되돌아감 | `abort` 후 최신 develop 으로 다시 적용. 머지 후엔 손으로 apply 하지 않는다 |
| 동기화가 멈추고 HPA 가 `unable to get the target's current scale: rollouts ... not found` | HPA 가 Rollout 보다 먼저 만들어짐 (#224 수정 전 클러스터) | `kubectl -n snipy apply -f infra/k8s/overlays/dev/<서비스>-rollout.yaml` 로 Rollout 을 먼저 만든다 |
| Deployment 와 Rollout 파드가 같이 떠 있고 워커 CPU 가 포화 | Rollout 이 Healthy 가 되기 전엔 Deployment 를 줄이지 않는다 (`scaleDown: onsuccess`). CPU 가 부족하면 Rollout 이 못 떠서 서로 기다림 | Rollout stable 파드가 Ready 인지 확인 후 `kubectl -n snipy scale deploy <서비스> --replicas=0` |

## 8. 되돌리기 (롤링 업데이트로)

dev overlay 에서 해당 서비스의 `*-rollout.yaml`, `*-canary-analysis.yaml`을 빼고 `patch-hpa.yaml`의 `scaleTargetRef`를 Deployment 로 돌린다.
Rollout 을 지우기 전에 Deployment 를 먼저 늘려야 서비스가 끊기지 않는다.

```bash
kubectl -n snipy scale deploy redirect-service --replicas=2
kubectl -n snipy rollout status deploy/redirect-service
kubectl -n snipy delete rollout redirect-service
```