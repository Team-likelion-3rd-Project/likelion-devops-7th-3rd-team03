# 무상태 점검 (HPA 대비)

파드가 여러 개로 늘거나 교체돼도 문제가 없는지, 상태가 어디에 저장되는지 항목별로 확인했다 (BE5).
상태가 파드 밖(DB·Redis·브라우저)에 있으면 어느 파드가 요청을 받아도 같고, 파드 메모리·디스크에 있으면 교체 때 사라질 수 있다.

## 결과

| 항목 | 상태 위치 | 판단 | 근거 |
|---|---|---|---|
| 로그인 세션 | 없음 (`SessionCreationPolicy.STATELESS`) | 무상태 | management `SecurityConfig` |
| 액세스 토큰 (JWT) | 토큰 자체. 서명 키는 Secret(`JWT_SECRET`)으로 모든 파드 동일 | 무상태 | |
| 리프레시 토큰 | DB `refresh_tokens` (해시 저장) | 무상태 | `RefreshTokenRepository` |
| 소셜 가입 대기 세션 | Redis (TTL 15분) | 무상태 | `SignupSessionStore` |
| OAuth state (CSRF 방지) | 브라우저 sessionStorage | 무상태 (서버 저장 없음) | 프론트 `auth/providers.js` |
| 리다이렉트 캐시 | Redis. 파드 메모리 캐시 없음 | 무상태 | `@Cacheable`·로컬 Map 없음 |
| 방문자 식별 | 브라우저 쿠키 `visitor_id` | 무상태 | redirect `app.redirect.visitor-cookie-*` |
| 스케줄 작업 | 파드별 버퍼 flush, 일별 집계는 CronJob | 중복 실행 없음 | [DB 커넥션 풀·스케줄러 락](db-connection-pool.md) |
| 실시간 통계 버퍼 | 파드 메모리 (최대 5초치) → Redis | 정상 종료는 flush. 강제 종료 시 최대 5초치 유실 | #107 (`RealtimeStatsRecorder` SmartLifecycle) |
| 클릭 로그 (온프렘) | 파드 볼륨(emptyDir) → Fluent Bit → SeaweedFS | 정상 교체 유실 0. 강제 종료 시 버퍼 유실 가능 | [롤링 배포 유실 측정](rolling-update-loss-test.md) |
| 비동기 이벤트 대기열 | 파드 메모리 (redirect `AsyncEventConfig` 스레드 풀) | **확인 필요** (아래) | |

## 판단

- **HPA로 파드를 늘리거나 줄여도 사용자 기능(로그인, 리다이렉트, 통계 조회)에는 영향이 없다.** 인증·캐시·세션이 모두 파드 밖에 있다.
- 파드 메모리·디스크에 남는 것은 **클릭 기록 경로**뿐이다 (실시간 버퍼, 클릭 로그 파일, 비동기 대기열).
  정상 종료에서는 유실 0을 측정했고, 강제 종료(OOMKilled, 노드 다운)에서는 짧은 구간이 사라질 수 있다.
  실시간 통계는 잠정값이고 최종 통계는 클릭 로그로 집계하므로, 실시간 버퍼 유실은 최종 통계에 영향이 없다.

## 확인 필요: 종료 시 비동기 대기열

redirect는 클릭 이벤트를 스레드 풀 대기열에 넣어 처리한다 (조립 → 로그 기록 / Firehose 전송).
`AsyncEventConfig`에 종료 시 대기열을 끝까지 처리하는 설정(`setWaitForTasksToCompleteOnShutdown`, `setAwaitTerminationSeconds`)이 없다.

- 저부하(초당 4건) 교체에서는 대기열이 거의 비어 있어 유실 0으로 측정됐다.
- 고부하 중 교체에서는 종료 순간 대기열에 남은 이벤트가 버려질 수 있다.

고부하 상태에서 배포 중 유실을 측정한 뒤(후속), 유실이 있으면 위 설정을 추가하고 다시 잰다.

## 하지 않은 것

- 강제 종료(파드 즉시 삭제, OOMKilled) 시 유실량 측정 → BE6 장애 시 동작에서