# 부하테스트 시나리오

## 서비스 컨텍스트

snipy는 URL 단축 + 클릭 분석 서비스. 타겟 고객은 자체 인프라 없이 SNS로 홍보/분석해야 하는 마케터·인플루언서·온라인 셀러.

`GET /{slug}` (redirect-service)는 임의의 외부 방문자에게 노출되는 엔드포인트라 트래픽 변동이 크고 예측 불가 — 특정 링크가 바이럴되면 순간적으로 대규모 트래픽이 몰릴 수 있다. 반면 인증/링크 관리(management-service)는 내부 인증 사용자만 쓰기 때문에 트래픽이 안정적이다. **그래서 부하테스트는 redirect-service에 집중한다.**

## 트래픽 규모 추정

- 내부 사용자 1만 명, 평균 10개/월 링크 생성 → 신규 링크 10만 개/월
- 캠페인 평균 수명 3개월 → 동시 활성 링크 ≈ 30만 개 (Little's Law: 10만/월 × 3개월)
- 링크당 평균 클릭 200/월 → 전체 트래픽 30만 × 200 = 6,000만/월
- 6,000만/월 ÷ 30일 = 200만/일 → 200만 ÷ 86,400초 ≈ **평균 23 TPS**
- 피크 시간대(저녁/점심 등 집중, 국내 서비스라 시차 분산 없음)는 평균의 약 10배로 추정 → **피크 ~230 TPS** (실측 데이터 없는 가정치 — 서비스 운영 후 재검증 필요)
- 바이럴 링크: 250만 명이 1시간 내 특정 링크로 몰리는 경우 → 250만 ÷ 3,600초 ≈ 694 TPS, 세이프티 마진을 얹어 **바이럴 목표 800 TPS**

### 성능 요구사항

| 항목 | 목표 |
|---|---|
| 평상시 Throughput | ~230 TPS |
| 바이럴 Throughput | ~800 TPS |
| Latency | **p99 < 150ms** |
| 에러율 | **0%** |

시간 제약상 **Load / Stress / Spike / Smoke 4가지**를 확실하게 보여주는 데 집중한다 (Smoke는 `redirect-smoke.js`로 이미 있음, Load/Stress는 `normal-traffic.js` 하나를 `PEAK_TPS`만 바꿔서 재사용, Spike는 `viral-spike.js`). Breakpoint/Soak은 후속 라운드로 미룸.

## 시나리오 1: Load / Stress (겸용)

`normal-traffic.js` 하나를 `PEAK_TPS` 값만 바꿔서 두 시나리오에 재사용한다 — Load는 낮은 TPS(예: 23), Stress는 높은 TPS(예: 230)로 실행. 처음엔 "비인기 시간대→퇴근시간 피크" 곡선(여러 단계 ramp)으로 짰다가, 분석이 복잡해져서 **상승 5분 → 유지 10분 → 하강 5분(총 20분)**의 단순한 사다리꼴로 정리했다.

### 테스트 파라미터

- 스크립트: `load-test/normal-traffic.js`
- Executor: `ramping-arrival-rate`, 0 → `PEAK_TPS`로 5분 상승 → 10분 유지 → 5분 하강. 총 20분
- 트래픽 분포(80/20 원칙): 링크 풀 하나(`SLUG_PREFIX` + `SLUG_COUNT`, 기본 30만 개 — 위 "동시 활성 링크" 추정치와 동일)를 생성하고, 그 안에서 앞쪽 `HOT_COUNT`개(절대값, 기본 50개)를 인기 구간으로 취급 — 요청의 80%(`HOT_TRAFFIC_RATIO`)는 그 구간에서, 나머지 20%는 뒤쪽 콜드 구간에서 균등 랜덤으로 뽑는다. 별도 풀을 만들 필요가 없어 INSERT는 단순함.
  - **비율이 아니라 절대값으로 잡은 이유**: 처음엔 hot을 "전체의 20%"로 잡았는데, 30만 개의 20%면 6만 개라 80% 트래픽으로도 웜업에 10분 넘게 걸려서 테스트 대부분 동안 hot 구간도 사실상 콜드로 남아 DB 부하가 계속 심했다(실제로 겪음). `HOT_COUNT`를 몇십 개로 작게(절대값) 잡으면 초반 수십 초 안에 다 캐싱되고, 이후엔 "hot은 거의 히트, cold는 거의 미스"가 깨끗하게 유지된다.
- 실행(원스톱): `load-test/normal-traffic-run.sh <context> [base-url] [num-links] [peak-tps]` (기본값: 30만 개, 230 TPS)

### 판정 기준

- 목표: p99 latency < 150ms, 에러율 0%
- 캐시 히트율이 시간이 지나며 어느 수준으로 수렴하는지 관찰 (Grafana `Cache Result Rate` 패널)

### 데이터 파이프라인 정확성 검증 (Athena)

앱 API로는 클릭 로그 파이프라인(redirect-service → Kinesis Firehose → S3 → Athena) 자체가 정상 동작하는지 확인할 방법이 없어서, Athena 쿼리로 직접 본다. `normal-traffic.js`는 VU(가상 사용자)마다 User-Agent/Referer/`visitor_id` 쿠키 조합을 고정 배정해서 보내므로(같은 방문자는 항상 같은 조합 — 실제 세션처럼), UA 파싱(yauaa)/referrer 분류/visitor 식별이 정확히 됐는지 사후 검증이 가능하다.

**주의**: `viral-spike-run.sh`/`normal-traffic-run.sh`는 테스트 직후 `trap cleanup EXIT`로 링크를 바로 지운다. click 이벤트 자체는 MySQL 행과 무관하게 이미 Kinesis로 나간 상태라 상관없지만, **Athena 배치(`link_daily_stats` 집계)가 나중에 도는 경우 이미 삭제된 `link_id`를 참조하게 되어 FK 위반으로 실패한다.** 파이프라인을 raw click_events(S3/Athena) 레벨까지만 볼 거면 cleanup 그대로 둬도 되고, `link_daily_stats` 집계까지 보고 싶으면 이번 실행만 cleanup을 건너뛰고 나중에 수동으로 지워야 한다.

**격리 기준**: 다른(실제) 트래픽과 섞여도 결과가 헷갈리지 않으려면 `visitor_id`나 `referrer`가 아니라 **`link_id`(테스트 전용 slug)로 필터링**하는 게 안전하다 — referrer(Instagram 등)나 visitor_id는 실제 방문자 값과 이론상 겹칠 수 있지만, 테스트 전용 slug는 실제 트래픽이 절대 안 침. `visitor_id`는 대신 "이 세션에 보낸 헤더가 정확히 파싱됐는지" 세션 단위로 대조하는 용도로 쓴다.

**일관성 자체 검증 쿼리** (기대값을 몰라도 됨 — 같은 visitor_id가 요청마다 다른 파싱 결과를 냈다면 버그):
```sql
SELECT visitor_id,
       COUNT(DISTINCT device_type || '|' || operating_system || '|' || browser || '|' || referrer_category) AS distinct_combos
FROM snipy_click_logs.click_events
WHERE year='2026' AND month='08' AND day='20'
  AND from_iso8601_timestamp(clicked_at) BETWEEN TIMESTAMP '2026-08-20 15:06:00' AND TIMESTAMP '2026-08-20 15:19:00'
GROUP BY visitor_id
HAVING COUNT(DISTINCT device_type || '|' || operating_system || '|' || browser || '|' || referrer_category) > 1
```
정상이면 0건. 한 방문자가 여러 조합으로 잡히면 파싱/식별 어딘가 문제가 있다는 뜻.

**실제 값 스팟체크** (k6 Job 로그에 `visitor VU=.. visitor_id=.. ua_profile=.. referrer_profile=..`로 배정 내역이 남음 — 그 visitor_id로 조회해서 UA_PROFILES/REFERRER_PROFILES 배열의 기대값과 눈으로 대조):
```sql
SELECT visitor_id, device_type, operating_system, browser, referrer_category, COUNT(*) AS clicks
FROM snipy_click_logs.click_events
WHERE year='2026' AND month='08' AND day='20'
  AND visitor_id = '<k6 로그에서 확인한 visitor_id>'
GROUP BY visitor_id, device_type, operating_system, browser, referrer_category
```

## 시나리오 2: 바이럴 스파이크 — "Warmup 없는 바이럴 링크"

### 가설

캐시에 없는 링크가 갑자기 바이럴되면(사전 warmup 없이), 동시에 몰리는 캐시 미스가 MySQL/HikariCP 커넥션 풀에 짧고 날카로운 병목을 만든다.

### 근거 (코드 확인 완료)

`RedirectService.findDatabaseRedirectTarget` (redirect-service)의 캐시 미스 처리:
1. `linkRepository.findBySlug`로 MySQL 조회
2. 결과를 `redirectCache.put`으로 Redis에 write-back

이 경로에 **동시성 보호가 전혀 없음** — 락, `synchronized`, 분산 락(Redisson 등), 요청 병합(request coalescing) 중 아무것도 구현돼 있지 않다. 동시에 도착한 요청은 각자 독립적으로 MySQL을 조회하고 각자 캐시를 덮어쓴다. TTL은 10분 고정이라, 테스트를 5분 안으로 잡으면 이 herd는 **테스트 시작 시점에 딱 한 번**만 발생한다 (재발 없음 — TTL 만료로 인한 동시 재캐싱까지 보고 싶으면 별도 라운드에서 테스트 duration을 TTL 이상으로 늘려야 함).

herd 크기는 "첫 DB 조회+캐시 write-back 왕복시간" 동안 도착하는 요청 수로 결정된다. 진짜 병목은 MySQL 자체보다 **HikariCP 커넥션 풀**일 가능성이 높다 (풀 사이즈를 넘는 요청은 대기 → 지연 급증). Grafana 대시보드(`grafana-dashboards.yaml`)에 이미 HikariCP 패널이 있어서 이 순간이 바로 잡힌다.

### 테스트 파라미터

- 스크립트: `load-test/viral-spike.js`
- Executor: `ramping-arrival-rate` (VU 기반이 아닌 목표 TPS 기반 — latency가 늘어도 요청 발생률이 줄지 않도록)
- 프로파일: 0 → 800 TPS를 10초 만에 램프업(바이럴의 "갑자기 몰림" 표현) → 4분 30초 유지 → 20초 램프다운. 총 5분
- 대상: 사전 캐싱되지 않은 콜드 링크. herd를 더 크고 뚜렷하게 보고 싶으면 콤마로 여러 개(3~5개) 동시 지정 가능
- 대상 URL: 클러스터 내부 Service DNS(`http://redirect-service:8080`, 기본값). 공개 도메인은 WAF geo-match(KR만 허용) 때문에 클러스터(ap-southeast-1) 안에서 치면 CloudFront가 403으로 막는다 — 실제로 겪은 문제. 이 테스트 대상은 애초에 CDN이 아니라 redirect-service 백엔드라서 내부 DNS가 더 맞기도 함
- 실행(원스톱): `load-test/viral-spike-run.sh <context> [base-url] [num-slugs]`

### 콜드 링크는 어떻게 만드나

management-service API로는 못 만든다 — API로 생성하면 트랜잭션 커밋 직후 바로 Redis에 캐시가 써져서(`RedirectCacheRefreshEventListener`, `AFTER_COMMIT`) 처음부터 웜 상태가 되고, slug도 서버가 SecureRandom으로 자동 생성해서 원하는 값을 못 지정하며, API 호출 자체에 카카오 OAuth 로그인이 필요해 자동화하기 번거롭다.

그래서 `load-test/viral-spike-run.sh`가 MySQL에 **직접 INSERT**해서 콜드 링크를 만든다 (캐시 이벤트를 타지 않아 자연히 콜드 상태, slug도 원하는 대로, 로그인 불필요). RDS는 노드 보안그룹에서만 열려있어 로컬에서 직접 접속은 안 되고, 클러스터 안에 `kubectl run`으로 임시 mysql 클라이언트 파드를 띄워 실행한다. 테스트 후 INSERT한 링크는 자동으로 DELETE된다 (`trap cleanup EXIT`).

### 사전 준비 (실행 전 체크리스트)

1. `load-test/README.md`의 사전 준비(observability 스택 배포, Grafana 접근) 완료
2. kubectl context가 MFA 인증된 상태
3. `aws secretsmanager get-secret-value` 권한 (DB 비밀번호 조회용)

### 관찰 포인트 (Grafana)

- `redirect_cache_result_total`: t=0 근처 miss 급증 → 이후 hit로 수렴하는지
- HikariCP Connection Pool 패널: t=0 순간 active/pending 커넥션이 튀는지 (예상되는 실제 병목 지점)
- `redirect_outcome_total`: t=0 구간 지연/에러 발생 여부
- k6 대시보드(grafana.com id 19665): p95/p99 latency가 t=0 구간만 튀고 정상화되는지, `http_req_failed` 비율

### 판정 기준 (초안 — 확정 필요)

- herd 구간(첫 1~2초) 동안 5xx 비율 < ?% — **미정, 1차 실행 결과 보고 정함**
- herd 이후(예: t=5초~) 안정화 구간의 p99 latency < 150ms(성능 요구사항) 유지 여부
- 테스트 전체에서 502/504(타임아웃/과부하로 인한 게이트웨이 오류) 발생 여부

### 1차 실행 결과 (2026-08-20, dev)

캐시 미스 herd(원래 가설)를 보기도 전에 **완전히 다른, 더 근본적인 병목**이 먼저 나타나서 락 가설은 아직 검증 못 함.

**겪은 문제 1 — WAF가 클러스터 내부 트래픽을 막음.** 처음엔 `BASE_URL`을 공개 도메인(`https://dev.snipy.life`)으로 뒀는데 100% 실패. `curl -v`로 확인해보니 `server: CloudFront`, "Request blocked" — WAF의 `waf_allowed_countries=["KR"]` geo-match 규칙에 걸림. k6 Job이 뜨는 EKS 노드가 `ap-southeast-1`(싱가포르)이라 NAT Gateway 아웃바운드 IP도 싱가포르로 잡혀서 CloudFront가 비KR로 판정하고 차단. **해결**: 테스트 대상을 클러스터 내부 Service DNS(`http://redirect-service:8080`)로 변경 — 이 테스트는 애초에 CDN이 아니라 백엔드가 대상이라 더 적합하기도 함.

**겪은 문제 2 — redirect-service 파드가 CPU 부족으로 쓰로틀링.** 내부 DNS로 수정 후 재실행하니 체크는 100% 통과(에러 없음)했지만 latency가 avg 1.02s, p95 2.72s, max 4.99s로 매우 높았고 `dropped_iterations: 94245`(k6가 목표 800 TPS를 못 만들어냄)까지 발생. Grafana에서 캐시 히트율은 실제로 높게 나와서(write-back은 정상) 처음엔 "캐시 조회 자체가 느리다"고 해석했으나, Prometheus에서 직접 확인해보니:

| 메트릭 | 평소 | 테스트 피크(15:08~15:10) |
|---|---|---|
| ElastiCache `EngineCPUUtilization` | ~0.2~0.3% | ~2.5% (거의 그대로, 병목 아님) |
| redirect-service `container_cpu_cfs_throttled_periods_total` rate | 0.01~0.14/s | **43~46/s** (300배+ 급증) |
| redirect-service CPU 사용량 합계 (limit=2파드×500m=1.0 vCPU) | - | 2.6~2.7 (vCPU 단위, limit 초과) |

Redis는 멀쩡했고, **redirect-service 파드(고정 2개, CPU limit 500m×2=1.0 vCPU)가 심하게 쓰로틀링**되고 있었다. "캐시 조회가 느림"으로 보였던 건 Redis 응답이 느린 게 아니라, 스로틀링당한 JVM 스레드가 커널한테 CPU 시간을 못 받아 그 안에서 실행되던 Redis 호출까지 같이 멈춰있던 것.

**결론**: 800 TPS는 지금 정적 capacity(2파드, HPA 없음)로는 램프 속도와 무관하게 도달 불가 — 지속 부하가 ~450 TPS를 넘는 순간 스로틀링이 시작된다. 락 가설(캐시 미스 herd → DB/HikariCP 병목)을 순수하게 보려면, **이번 스파이크 재실행 전에 CPU capacity 문제부터 없애야 한다** (파드 CPU limit 상향, 필요시 replicas 임시 상향 — 아래 "후속 계획" 참고). CPU capacity 부족 자체는 "정적 replica라 오토스케일링이 스파이크에 못 따라간다"는 별도의 유효한 결론으로 남겨둔다.

**겪은 문제 3 (가장 근본적) — k6가 URL을 그대로 메트릭 라벨로 써서 Prometheus 카디널리티가 폭발.** 이후 HPA 실험(30만 개 링크 + 800 TPS `normal-traffic.js`) 도중 Prometheus 자체가 크래시루프에 빠졌다(6회 재시작, WAL replay 44초+, startup probe 타임아웃으로 계속 재시작). `/api/v1/status/tsdb`로 확인해보니 **시계열이 87만 개**, 그중 k6 HTTP 메트릭 9종류가 각각 **85,111개**씩 — `http.get()` 호출에 `tags: { name: ... }`을 안 줘서 k6가 요청 URL(슬러그별로 전부 다름, 최대 30만 가지)을 그대로 메트릭 라벨로 써버린 것. Prometheus 리소스를 올려도(500m/1Gi → 1500m/2Gi) 근본 원인은 이거였다. 추가로 Grafana에 import했던 공식 k6 대시보드(id 19665)가 `testid` 라벨 기준으로 필터링하는데 우리는 그 라벨을 안 써서 `testid=~"()"`(사실상 전체 매칭)라는 무거운 쿼리를 계속 날리고 있었던 것도 확인됨 — 카디널리티 폭발과 겹쳐서 크래시를 더 악화시켰을 것으로 추정.

**해결**: `viral-spike.js`/`normal-traffic.js`/`redirect-smoke.js` 전부 `http.get()`에 `tags: { name: "redirect" }`(normal-traffic.js는 hot/cold 구분까지, 값 2개뿐이라 안전) 추가 — 이제 슬러그가 몇 개든 메트릭은 고정된 이름 몇 개로만 쌓인다. id 19665 대시보드는 라벨 체계가 안 맞아서 계속 안 쓰기로 함(Explore로 직접 PromQL 조회). 이미 쌓인 87만 개 시계열은 retention(3d) 지나면 자연히 빠짐 — 강제로 안 지움.

## 후속 계획

동시성 보호 적용 전/후 비교로 개선 효과를 데이터로 증명하는 것까지가 목표. 순서:

0. **CPU capacity 문제 먼저 해소**: redirect-service 파드 CPU limit을 넉넉히 올리고(예: 500m→1500m~2000m), 이번 재테스트에 한해 replicas도 임시로 늘려서(HPA 반응 속도 이슈 회피) 800 TPS를 지속 부하로도 감당할 수 있는 상태를 먼저 만든다. 그래야 아래 1~3단계에서 관찰되는 지연이 진짜 캐시/DB 이슈인지 순수하게 구분됨
1. **베이스라인**: 보호 없음 — 위 시나리오 그대로, capacity만 확보된 상태로 재실행
2. **in-process 락**: 슬러그별 JVM 로컬 락(`synchronized` 또는 Caffeine single-flight). 구현 빠름(~1시간). 한계: 파드 개수만큼은 여전히 중복 조회됨 (파드 경계를 못 넘음)
3. **Redis 분산 락**: `SET lock:{slug} NX PX 3000` + 락 획득 실패 시 짧은 폴링(캐시가 채워지길 기다림) → 몇 번 폴링해도 안 채워지면 안전장치로 직접 조회. 기존 `RedisRedirectCache`의 Lua 스크립트 패턴 재사용 가능, 새 라이브러리 불필요. 클러스터 전체에서 DB 조회를 사실상 1번으로 수렴시킴

2/3단계 진행 여부와 순서는 1차 테스트 결과를 보고 결정.

## 다른 시나리오 (초안 — 이번 라운드에 실행 안 함)

- **Breakpoint test**: TPS를 한계까지 점진적 증가. 종료 조건(에러율/latency 임계치 또는 안전 상한 TPS) 필요 — prod RDS가 `db.t3.micro`라 여기서 DB가 먼저 무너질 것으로 예상(가설)
- **Soak test**: 1시간 — 진짜 leak-hunting용 soak(보통 수 시간 이상)이라기보다는 짧은 안정성 체크로 취급

## 파드 HPA / 노드 오토스케일링 튜닝 (별도 트랙)

1. HPA 끈 상태로 단일 파드 최대 TPS 측정
2. 목표 성능 기준 필요 파드 수 계산
3. 필요 노드 수 산출
4. HPA + Cluster Autoscaler 켜고 전체 테스트, 병목 위치 확인

**전제 조건**: 현재 redirect-service/management-service는 `replicas: 2` 고정이고 HPA 리소스 자체가 없음 (metrics-server만 설치됨). 이 트랙을 시작하려면 HPA를 먼저 추가해야 함.

**중요 제약**: Cluster Autoscaler의 노드 추가는 보통 1~3분 걸리는데, 바이럴 스파이크 테스트는 5분짜리라 초반 스파이크는 오토스케일링이 반응하기 전에 지나갈 수 있음. 스파이크 대응력은 사실상 사전 확보된 여유 capacity(HPA `minReplicas`)에 좌우된다는 점을 감안해서, "cold(기본 상태에서 시작)" vs "warm(미리 스케일업된 상태)" 스파이크를 나눠 테스트하면 오토스케일링의 실질적 기여도를 수치로 보여줄 수 있음.

### Step 1 — 단일 파드 성능 측정 (2026-08-21, dev)

HPA를 끄는 대신 Service를 거치지 않고 파드 하나의 **Pod IP로 직접** 부하를 줘서 격리(`load-test/breakpoint.js`, `breakpoint-run.sh`). 과정에서 병목 2개를 찾아 수정:

1. **HikariCP pool 8 → 16**: `application.yml` 기본값(`DB_HIKARI_MAX_POOL_SIZE:8`)이 작아서 늘림.
2. **JVM 힙 미설정**: Dockerfile이 `-Xmx` 없이 `java -jar app.jar`만 실행 → 컨테이너 메모리 limit(1Gi)의 기본 25%(`MaxRAMPercentage`)만 힙으로 잡혀 **max heap ~250MB(Eden 68MB)**. 부하 중 major GC pause가 wall-time의 7~10%를 차지해 p99를 750~800ms까지 밀어올림. `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0` 추가로 max heap을 **~742MB**로 늘려서 해결 (이미지 리빌드 불필요 — JVM이 시작 시 자동으로 읽는 표준 env var).

**수정 후 현재 spec** (dev, 2026-08-21 기준):

| 항목 | 값 |
|---|---|
| redirect-service CPU | request 250m / limit 500m |
| redirect-service Memory | request 512Mi / limit 1Gi |
| JVM heap | `JAVA_TOOL_OPTIONS=-Xms512m -Xmx512m` (고정값, Step 2에서 퍼센트 방식→고정값으로 변경) |
| HikariCP pool | max 8(Step 2에서 16→8로 축소), min-idle 2 |
| RDS | `db.m6g.large` |
| ElastiCache | `cache.t4g.micro` |
| 노드 | `m5.large` × 3 |
| HPA | min 2 / max 6, target CPU 70% |

**단일 파드 결과 (100 TPS, 10만 링크, 10분 — `normal-traffic.js` 80/20 hot/cold)**: p95=**6.82ms**, p99=**51.61ms**, 에러율 0% — SLA(p99<150ms) 대비 압도적 여유. GC/Hikari 수정 전(p95=528ms, p99=783ms) 대비 개선폭이 매우 큼 → 진짜 병목은 CPU/DB가 아니라 GC였다는 뜻.

### Step 2 — 파드당 200 TPS 목표로 비용 효율화 시도 (2026-08-21, dev) — 실패, 원복

목표: p95<100ms, p99<300ms를 유지하면서 파드당 200 TPS를 처리하도록 리소스를 낮춰서 비용 절감.

**breakpoint 결과 (현재 스펙, 캐시히트 100% 풀 20개)**: TPS를 계속 올리다 8분22초/목표 TPS ~1670에서 p95>1000ms로 자동 중단(에러율은 끝까지 0%) — 500m CPU에서 순수 캐시히트 한계는 대략 1,600~1,700 TPS.

**캐시히트 전용 데이터로 리소스를 줄이려다 한 번 실패**: breakpoint(캐시히트 100%)의 CPU/req(0.436ms)로 계산한 CPU 300m는, 실제 80/20 믹스 트래픽에서 나온 CPU/req(0.61ms, 캐시미스가 더 비쌈)로 다시 계산하면 부족했음. 우연히 남아있던 800 TPS 믹스 트래픽 실측 데이터(CPU 500m에서 0.488 core=97.6% 포화, cold p95=157ms로 이미 SLA 탈락 직전)로 재계산해서 **CPU limit 300~400m** 선으로 수정.

**실제 적용 시도 (CPU request 150m/limit 300m, Memory 384Mi/limit 768Mi, heap 고정 512MB)에서 크래시루프 발생**: JVM 콜드스타트(클래스 로딩+Spring 컨텍스트 초기화)가 정상 서빙보다 CPU를 훨씬 많이 씀. startupProbe 150초 안에 못 떠서 kubelet이 반복 kill → HPA가 불안정 판단해 파드 6개까지 증설 → 여러 파드가 같은 노드에서 CPU 경쟁하며 서로 더 느려지는 악순환. exitCode 143(SIGTERM, startup probe timeout)으로 확인.

**교훈**: request와 limit을 동시에 낮추면 위험함. request는 "보장된" 몫이고 limit까지의 버스트는 노드에 여유가 있을 때만 가능한데, 여러 파드가 한꺼번에 재시작하는 상황(정확히 이번에 겪은 시나리오)에서는 노드에 여유가 없어 결국 request만큼만 받음 — "request 낮게, limit 높게"도 최악의 경우엔 구원되지 않음. 안전하게 재시도하려면 CPU를 낮추기 전에 **`startupProbe.failureThreshold`를 늘려서**(현재 150초 → 예: 300초) 콜드스타트가 느려도 죽지 않게 시간 쪽으로 마진을 먼저 확보해야 함.

**최종 결정 (2026-08-21)**: CPU/메모리는 검증된 값(request 250m/limit 500m, request 512Mi/limit 1Gi)으로 원복하고 비용 튜닝은 보류. 아래는 유지:
- HikariCP pool: 16 → **8**로 축소 유지 (DB 트래픽이 캐시히트율 감안 시 여유 충분해서 문제 없었음)
- JVM heap: `JAVA_TOOL_OPTIONS`을 퍼센트(`MaxRAMPercentage`) 대신 **절대값 고정** (`-Xms512m -Xmx512m`)으로 변경 유지 — 컨테이너 memory limit을 나중에 바꿔도 힙 크기가 같이 흔들리지 않게 하기 위함.

파드당 200 TPS 비용 효율화를 다시 시도하려면: (1) `startupProbe.failureThreshold`를 먼저 늘려서 콜드스타트 타임아웃 위험 제거, (2) request만 낮추고 limit은 그대로 두는 대신 **실제 재시작이 몰리는 상황(롤링 재배포, 다중 파드 동시 재시작)까지 포함해서 검증**, (3) 필요하면 JVM 콜드스타트 자체를 가볍게 하는 옵션(`-XX:TieredStopAtLevel=1` 등)도 검토.

### Step 2 후속 — CPU를 250m/500m로 원복한 뒤에도 HPA 스케일아웃 시 latency 스파이크 재현 (2026-08-21, dev)

원복 후 800 TPS 부하테스트(`normal-traffic.js`) 중 **p99가 순간적으로 8.3초(cold)/993ms(hot)까지 튐** — 정확히 HPA가 파드를 4→6개로 늘리는 순간(14:04:30~14:05:45)에 발생. 타임라인으로 원인 확인:

| 시각 | 파드 수 | CPU throttle(전체 합, periods/s) | Hikari pending(전체 합) | k6 VUs |
|---|---|---|---|---|
| 14:04:00 | 4 | 36 | 0 | 16 |
| 14:04:45 | 6 (막 스케일아웃) | 43 | 62 | 240 |
| 14:05:15 | 6 | 65 | 166 | 270(피크) |
| 14:05:30 | 6 | **72.7(피크)** | **169(피크)** | 224 |
| 14:06:30 | 6 | 70.5 | 0 | 21 |

**연쇄 반응**: HPA가 새 파드 2개를 투입 → 새 파드는 CPU/JIT 콜드 상태라 throttling 폭증(합계 최대 74.6/s, 여러 파드가 동시에 워밍업 중이라 겹침) → 처리 지연으로 DB 커넥션을 평소보다 오래 붙잡음 → Hikari pool(6파드×8=48) 사실상 고갈, pending 169까지 쌓임 → k6(open-workload, `ramping-arrival-rate`)가 응답 지연을 감지하고 목표 TPS를 유지하려 VU를 240~270개까지 증폭 → 부하가 더 몰려 상황 악화 → 약 1분 반 뒤 워밍업 종료되며 자연 회복. Major GC는 이 구간 내내 0 — GC는 무관.

**결론**: Hikari pool 크기나 GC가 아니라 **HPA 스케일아웃 시 새 파드의 CPU 콜드스타트가 방아쇠**이고, 그게 커넥션 풀 고갈로 전이되며, k6의 VU 증폭이 상황을 증폭시키는 연쇄 장애. Step 2의 크래시루프와 근본 원인이 동일함(콜드스타트가 CPU를 많이 씀) — 이번엔 CPU가 500m라 파드가 죽진 않았지만, 대신 8초짜리 latency 스파이크로 나타남. **`startupProbe` 조정만으로는 이 문제를 못 막음**(파드가 죽지 않으니) — 스케일아웃 자체를 덜 갑작스럽게 하거나(HPA `behavior.scaleUp` 정책으로 증설 속도 제한), 콜드스타트 CPU 비용 자체를 줄이는(JIT 튜닝, AppCDS 등) 접근이 필요해 보임.
