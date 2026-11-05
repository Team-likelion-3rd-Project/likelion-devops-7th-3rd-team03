# prod 사양 결정을 위한 부하 테스트 기획 (#244)

운영 사양(`infra/terraform/env/prod/terraform.tfvars`)은 가용성을 우선해 크게 잡혀 있어서 하루 약 25달러가 든다. 부하 테스트로 목표 성능을 통과하는 **가장 작은 사양**을 찾고, 그 결과로 운영 사양을 정한다.

시나리오와 스크립트는 2차에서 만든 것을 그대로 쓴다 (`load-test/SCENARIOS.md`, `load-test/README.md`). 이 문서는 그것을 3차 prod 환경에서 어떤 순서로 돌리고, 결과로 무엇을 정하는지만 적는다.

## 1. 목표

`load-test/SCENARIOS.md`의 성능 요구사항을 그대로 쓴다.

| 항목 | 목표 | 근거 |
|---|---|---|
| 평상시 | 230 TPS | 월 6,000만 클릭 → 평균 23 TPS × 피크 10배 |
| 바이럴 | 800 TPS | 250만 명이 1시간에 몰림(694 TPS) + 여유 |
| 지연 | p95 < 150ms (스크립트 threshold), p99 < 150ms (SCENARIOS 요구사항) | |
| 에러율 | 0% | |

## 2. 범위

- **대상**: redirect-service (`GET /{slug}`). 외부 방문자에게 노출되어 트래픽 변동이 크다. management-service는 로그인한 사용자만 써서 이번 대상이 아니다.
- **경로**: k6가 클러스터 안에서 내부 Service DNS(`http://redirect-service:8080`)로 보낸다. 공개 도메인은 WAF의 KR 제한 때문에 클러스터(싱가포르)에서 막힌다.
- **제외**: CloudFront, WAF, ALB 구간은 측정하지 않는다. 관리형 서비스라 사양 결정과 관계가 적다.
- **함께 확인**: DB 연결 수 실측. #174(DB 연결 풀 계산)가 "prod 배포 후 확인"으로 남긴 항목이다.

## 3. 환경

### 3-1. 시작 사양 (1단계)

`infra/terraform/env/prod/test-lite.tfvars`로 운영 사양 위에 덮어쓴다.

| 대상 | 시작 값 | 운영 사양(현재) | 시작 값을 고른 이유 |
|---|---|---|---|
| NAT Gateway | 1개 | 2개 | 부하가 클러스터 안에서만 오간다. 개수는 가용성 문제다 |
| EKS 노드 | t3.medium 최소 3 / 최대 5 | m5.large 3 / 5 | 상주 파드가 3대에는 들어가는 것이 확인됨(2대는 부족). HPA 최대일 때 앱 requests가 4 vCPU라 cluster-autoscaler가 늘리는지도 본다 |
| RDS | db.t4g.medium, Single-AZ | db.m6g.large, Multi-AZ | t4g.micro는 max_connections(~80)가 HPA 최대 연결 수(~105)보다 작다. t4g.small(~150)은 여유가 적다 |
| ElastiCache | cache.t4g.micro 1노드 | 2노드 | 읽기는 primary로만 가서 복제본은 성능과 관계없다 |
| RDS 보호 | 삭제 보호 끔, 최종 스냅샷 생략 | 켬 | 시험 데이터는 남기지 않는다 |

비용은 시간당 약 0.5달러다 (운영 사양은 약 1.05달러).

### 3-2. 배포와 접속

1. terraform 워크플로 dispatch: `plan` → `apply`, spec `test-lite`, ArgoCD `develop` (부하 테스트 스크립트가 #206 이후 스키마를 쓴다)
2. kubectl 연결: `aws eks update-kubeconfig --name snipy-cluster --alias snipy-prod`
3. Grafana: `kubectl --context snipy-prod port-forward -n monitoring svc/kube-prometheus-grafana 3000:80`, 비밀번호는 `snipy-cluster/app/grafana-admin-password`
4. k6 대시보드(grafana.com id 19665)를 Import한다

## 4. 진행 순서

앞 단계가 목표를 통과해야 다음 단계로 간다. 단계 사이에 5분 쉬어 HPA가 원래대로 줄어들게 한다.

| 순서 | 시나리오 | 명령 | 시간 | 보는 것 |
|---|---|---|---|---|
| 1 | Load (23 TPS) | `load-test/normal-traffic-run.sh snipy-prod http://redirect-service:8080 300000 23` | 20분 + 링크 생성 | 배포·스크립트가 정상인지, 클릭 로그가 Athena까지 가는지 |
| 2 | Stress (230 TPS) | `load-test/normal-traffic-run.sh snipy-prod http://redirect-service:8080 300000 230` | 20분 | 평상시 목표 |
| 3 | 바이럴 (800 TPS) | `load-test/viral-spike-run.sh snipy-prod` (스크립트 기본값은 1,600 TPS. 800은 `SPIKE_TPS` 기본값을 바꿔 실행) | 5분 | 바이럴 목표, 콜드 링크 herd, 클릭 유실(5절 Firehose) |
| 4 | Breakpoint (선택) | `load-test/breakpoint-run.sh snipy-prod` | ~10분 | 파드 1개의 한계 TPS (2차 dev: 약 1,600~1,700) |

- 링크 30만 개는 MySQL에 직접 INSERT하고, 끝나면 스크립트가 숨김 처리한다 (soft delete, #255, `trap cleanup EXIT`).
- 각 실행의 `testid`를 기록해 Grafana에서 실행별로 나눠 본다.

## 5. 측정 지표

| 대상 | 지표 | 어디서 |
|---|---|---|
| k6 | p95, p99, 에러율, `dropped_iterations` | Grafana k6 대시보드, k6 Job 로그 |
| redirect 파드 | CPU 사용률, CPU throttling, 파드 수(HPA), 캐시 히트율, Hikari active/pending | Grafana |
| 노드 | CPU·메모리 사용률, **requests 합계(Allocated resources)**, 노드 수, Pending 파드 | `kubectl top nodes`, `kubectl describe nodes`, `kubectl get pods -A --field-selector=status.phase=Pending` |
| RDS | CPUUtilization, DatabaseConnections, FreeableMemory, ReadIOPS, CPUCreditBalance | CloudWatch |
| ElastiCache | EngineCPUUtilization, DatabaseMemoryUsagePercentage, Evictions, CurrConnections | CloudWatch |
| Firehose | IncomingRecords, ThrottledRecords (바이럴 구간 합계) | CloudWatch, redirect 로그 `Failed to send click event to Firehose` |
| DB 연결 수 | `max_connections`, `Max_used_connections` | 아래 SQL |

```sql
SHOW VARIABLES LIKE 'max_connections';     -- 추정 ~340 (db.t4g.medium)
SHOW STATUS LIKE 'Max_used_connections';   -- 시나리오가 모두 끝난 뒤. 계산값 ~105와 비교
```

클릭 로그는 302 응답과 따로 비동기로 보내져서, 유실돼도 k6 지표에는 보이지 않는다. 버려지는 곳은 두 군데다.

- Firehose 한도(Direct PUT, 리전 기본 한도는 Service Quotas에서 확인): `ThrottledRecords`로 보인다.
- redirect 앱의 전송 큐(`AsyncEventConfig`의 firehose executor, 스레드 1~2개·큐 10,000·`DiscardPolicy`): 로그 없이 버려진다. k6 `http_reqs` − `IncomingRecords` − `ThrottledRecords`로 계산한다.

노드 메모리 사용률이 낮아도 노드를 바로 줄이지 않는다. HPA 최대일 때 CPU requests 합계가 먼저 찬다.

## 6. 판정과 사양 조정

목표를 못 넘으면 병목인 자원만 한 단계 올리고, **실패한 시나리오만** 다시 돌린다. 사양을 바꿀 때마다 dispatch apply를 한 번 한다 (10~20분).

| 증상 | 올릴 것 |
|---|---|
| 노드 CPU 포화, Pending 파드, 노드가 최대 5대까지 참 | 노드 최대 대수, 또는 4 vCPU 노드 (m5.large는 2 vCPU라 노드당 CPU가 같다) |
| RDS CPU 70% 이상, 연결 오류, Hikari pending 지속 | db.t4g.large → db.m6g.large |
| Redis CPU 70% 이상, Evictions 발생 | cache.t4g.small |
| p99가 HPA가 파드를 늘리는 순간에만 튐 | 사양 문제가 아니다. 새 파드 콜드스타트 문제(SCENARIOS.md "Step 2 후속")라 따로 기록한다 |
| Firehose `ThrottledRecords` > 0 | 사양이 아니다. Service Quotas에서 Direct PUT 한도 증가를 요청한다 |
| `http_reqs` − `IncomingRecords` − `ThrottledRecords` > 0 | 사양이 아니다. 앱 전송 큐에서 버려진 것이라 redirect 코드 문제로 후속 이슈를 만든다(BE) |

- 노드 타입을 바꾸면 노드 그룹이 교체되어 파드가 모두 다시 뜬다.
- t3, t4g는 Unlimited 모드라 느려지지는 않고 기준선을 넘은 만큼 요금이 붙는다. CPUCreditBalance가 계속 줄면 평소 운영에서도 크레딧을 쓴다는 뜻이라, 결과에 같이 적는다.

## 7. 운영 사양 결정

부하 테스트를 통과한 사양에 가용성 설정을 더해 운영 사양을 정한다.

- **성능**: 통과한 노드, RDS, Redis 사양
- **가용성**: RDS Multi-AZ(RDS 비용 2배), Redis 복제본, NAT 2개. 부하 테스트와 관계없이 예산을 보고 정한다 (강사님께 확인 중)

결정한 값으로 `terraform.tfvars`를 고치는 PR을 올리고, 그다음 prod를 연다.

## 8. 주의

- k6 파드도 노드 CPU를 쓴다. 노드 사용률은 k6가 뜬 노드를 빼고 보거나 그 몫을 감안한다.
- 링크 30만 개 INSERT와 UPDATE는 RDS에 쓰기 부하를 준다. 시나리오 측정 구간과 겹치지 않는다.
- 클릭 로그가 Firehose → S3로 쌓인다. 테스트가 끝나고 destroy하면 같이 지워진다(`force_destroy`).
- 끝나면 바로 destroy한다: dispatch `destroy`, spec `test-lite`.

## 9. 결과 기록 (2026-11-05)

| 단계 | 사양 (노드 / RDS / Redis) | 시나리오 | testid | p95 / p99 | 에러율 | 최대 파드·노드 수 | RDS CPU·연결 | Redis CPU·메모리 | 판정 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | t3.medium 3~5 / t4g.medium / t4g.micro | Load 23 | lt861118 | 10.27 / 22.45 ms | 0% | redirect 2 / 노드 3 | - | - | 통과 |
| 1 | 〃 | Stress 230 | lt862929 | 7.44 / 21.75 ms | 0% | redirect 2 / 노드 3 | 최대 15%·20 | 3.7%·6.7% | 통과 |
| 1 | 〃 | 바이럴 800 | 1793870340-23052 | 99.91 ms / 최대 658 ms | 0% | redirect 6 / 노드 4 | - | - | p95 통과, p99 미통과 |
| 1 | 〃 (노드 최소 4) | 바이럴 800 | 1793871547-12206 | 142.74 ms / 최대 1.04 s | 0% | redirect 6 / 노드 4 | - | - | p95 통과, p99 미통과 |

- DB 연결 수: `max_connections` = 303, `Max_used_connections` = 20 (230 TPS, 파드 4개)
- RDS CPU 크레딧은 테스트 중에도 쌓였다 (33.9 → 41.1). Redis Evictions 0.
- 클릭 수
  - Stress: k6 `http_reqs` 206,980, Firehose `IncomingRecords` 206,978
  - 바이럴 800: k6 `http_reqs` 227,105, Firehose `IncomingRecords` 157,204 = `click_count` 합계 157,204, `ThrottledRecords` 0 → 앱 전송 큐에서 69,901건(30.8%) 유실. 급증 초반 파드 2개가 유입을 다 받는 동안 큐(10,000)가 차서 버렸다.

### 참고: 1,600 TPS (`viral-spike.js` 기본값)

| | probe 수정 전 | probe 수정 후 (#260) |
|---|---|---|
| 처리량 | 424 TPS | 709 TPS |
| p95 | 6.72 s | 1.82 s |
| 에러율 | 7.15% | 0% |
| liveness 재시작 | 파드 5개 | 0 |

- 수정 전: 과부하에서 liveness(timeout 1초)가 실패해 파드가 연쇄 재시작됐다. 노드 하나가 파드 수 한도(t3.medium 17개)에 걸리기도 했다 (`Too many pods`).
- 수정 후: 노드 4대 중 2대만 CPU 100%였다. redirect 파드가 몰린 노드다 (첫 실행에서는 k6도 그중 한 노드에 있었다).

### 바이럴 p99 원인

노드 4대로 시작해도 꼬리가 줄지 않았다. 이벤트 시각과 p99를 맞춰 보면, 처음 2분은 파드 2개로 800 TPS를 p99 100 ms 이하로 처리했다. HPA가 3 → 6으로 파드 3개를 동시에 띄운 직후(18:42:20) p99가 1.04 s까지 올랐다. 용량 부족이 아니라 새 파드의 JVM 콜드스타트 때문이다 (SCENARIOS.md "Step 2 후속"과 같은 현상).

### 결론

- 노드: **t3.medium 3대(최대 5)** 로 평상시·바이럴 처리량을 감당한다. 노드를 4대로 늘려도 바이럴 꼬리는 줄지 않았다.
- RDS: **db.t4g.medium** 은 여유가 크다 (CPU 15%, 연결 20/303). db.t4g.small(max_connections 약 150)도 후보지만 이번에 측정하지 않았다.
- Redis: **cache.t4g.micro** 로 충분하다.
- 6절 노드 행의 m5.large를 4 vCPU 노드로 고쳤다. m5.large도 2 vCPU라 노드당 CPU가 t3.medium과 같다.
- 사양으로 풀리지 않는 문제는 후속 이슈로 남긴다: 클릭 이벤트 유실(Firehose 배치 전송), 새 파드 콜드스타트(ALB slow start·워밍업 후 Ready·HPA 최소 파드 수), `viral-spike.js` 기본값과 `SPIKE_TPS` 전달.
- 가용성 설정(RDS Multi-AZ, Redis 복제본, NAT 2개)은 예산 확인 후 정한다.
