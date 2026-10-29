# DB 커넥션 풀 크기 근거 (BE4)

## 설정

| 항목 | 값 | 위치 |
|---|---|---|
| Hikari 최대 커넥션 (파드당) | 8 (`DB_HIKARI_MAX_POOL_SIZE`, dev는 4) | `application.yml`, `overlays/dev/configmaps.yaml` |
| Hikari 최소 유휴 | 2 (`DB_HIKARI_MIN_IDLE`) | `application.yml` |
| HPA (prod) | management 2~4, redirect 2~6 | `infra/k8s/base/hpa.yaml` |
| 롤링 업데이트 | maxSurge 25% (교체 중 +1개) | 각 Deployment |

## 최악의 경우 커넥션 수 (prod)

| 대상 | 계산 | 커넥션 |
|---|---|---|
| management | (최대 4 + 교체 중 1) × 8 | 40 |
| redirect | (최대 6 + 교체 중 1) × 8 | 56 |
| 집계 배치 CronJob | 1 × 8 | 8 |
| 마이그레이션 Job | mysql 클라이언트 | 1 |
| **합계** | | **약 105** |

## RDS 수용량

RDS MySQL 기본 `max_connections` = 인스턴스 메모리 / 12,582,880 바이트.

| 환경 | 인스턴스 | max_connections (대략) | 판단 |
|---|---|---|---|
| prod | db.m6g.large (8GiB) | 약 680 | 최대 확장 시에도 약 15% 사용 |
| test-lite | db.t4g.micro (1GiB) | 약 80 | **HPA 최대 확장 시 초과 가능** |

실제 값은 `SHOW VARIABLES LIKE 'max_connections';`로 확인한다.

## 판단

- 파드당 8개면 충분하다. redirect는 대부분 Redis 캐시에서 응답하고 DB는 캐시 미스일 때만 간다. 파드 CPU limit이 1코어라 커넥션을 늘려도 동시 처리량이 늘지 않는다.
- dev(온프렘)는 MySQL 파드 하나(메모리 1Gi)라 파드당 4개로 낮췄다.
- **test-lite(t4g.micro)로 띄울 때**는 HPA 최대를 줄이거나 `DB_HIKARI_MAX_POOL_SIZE=4`로 낮춘다.

## 스케줄러 락

여러 레플리카에서 같은 작업이 중복 실행되는지 확인했다.

| 작업 | 방식 | 중복 실행 | 락 필요 |
|---|---|---|---|
| 실시간 통계 flush (redirect `@Scheduled`) | 파드마다 자기 메모리 버퍼만 Redis에 반영 | 각자 다른 데이터 | 없음 |
| 클릭 일별 집계 | Kubernetes CronJob (한 번만 실행) | 없음 | 없음 |

`@Scheduled`는 위 하나뿐이라 ShedLock 등 분산 락은 필요 없다.

## Redis 타임아웃

`spring.data.redis.timeout=500ms`, `connect-timeout=2s` (#171). 기본 60초를 기다리지 않고 캐시 실패 시 DB로 넘어간다.
Redis가 응답하지 않을 때 redirect 응답: 18.98초 → 1.02초.


## prod 연결 확인 (prod를 띄울 때)

```bash
kubectl -n snipy port-forward deploy/redirect-service 18080:8080 &
curl -s localhost:18080/actuator/health | python3 -m json.tool   # components.db, components.redis 가 UP
kill %1
```

RDS에서 실제 한도와 현재 사용량:
```sql
SHOW VARIABLES LIKE 'max_connections';
SHOW STATUS LIKE 'Threads_connected';
```