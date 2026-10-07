# ERD 설계 문서 대비 변경 사항

원본 설계 문서(ERD + DDL 초안) 대비에서 달라진 부분을 정리합니다. 코드 리뷰 없이 이 문서만 보고도 "뭐가 왜 바뀌었는지" 알 수 있게 쓰는 게 목적입니다.

---

## 1. 테이블 개수: 4개 → 5개

**`link_daily_dimension_stats` 테이블 신규 추가.**

원본 설계엔 유입경로/기기/지역 분포를 낼 방법이 없었습니다. "모든 통계 API는 `link_daily_stats`만 참조"한다는 원칙과 통계 요구사항(유입 경로 분석)이 서로 충돌하는 지점이라, 이 테이블이 없으면 나중에 소급해서 만들 수도 없었습니다(원본 로그를 30일만 보관하기로 했으므로). 그래서 오픈 전 필수로 판단해 지금 추가했습니다.

```sql
CREATE TABLE link_daily_dimension_stats (
    id, link_id, stat_date, dimension_type, dimension_value, click_count
    -- UNIQUE(link_id, stat_date, dimension_type, dimension_value)
);
```

> 이 테이블도 `links`를 참조하는 FK를 가집니다 — 7번 항목의 FK 개수에 영향을 줍니다.

---

## 2. 시간 컬럼: `TIMESTAMP` → `DATETIME` (전 테이블)

원본은 `created_at`, `updated_at`, `expires_at`, `clicked_at` 전부 `TIMESTAMP`였습니다.

**바뀐 이유**: `TIMESTAMP`는 2038-01-19 이후 값을 저장 못합니다(Unix time 32bit 한계). `expires_at`에 "10년 뒤" 같은 값이 들어갈 수 있는 기능이 생기면 지금부터 문제가 됩니다. 게다가 MySQL 파티셔닝 표현식은 `TIMESTAMP`를 직접 못 쓰고 `UNIX_TIMESTAMP()`로 감싸야만 해서(ERROR 1486), 나중에 `click_events`를 월별 파티셔닝하려면 이 변경이 선행조건이었습니다.

**영향**: 저장/조회 방식은 동일하고, `DATETIME`은 9999년까지 저장 가능합니다. 다만 `DEFAULT CURRENT_TIMESTAMP`는 DB 서버의 `time_zone` 설정을 그대로 따르므로(`TIMESTAMP`와 달리 UTC로 내부 변환되지 않고 입력된 값이 그대로 저장됨), `clicked_at`을 UTC로 저장한다는 설계 결정이 실제로 지켜지려면 DB 서버의 `time_zone`이 UTC로 명시적으로 설정돼 있어야 합니다(별도 확인 필요 — 아래 "검토" 참조).

---

## 3. `links.slug` — 콜레이션 명시 (`CHARACTER SET ascii COLLATE ascii_bin`)

원본은 `slug VARCHAR(20) NOT NULL UNIQUE`로, 테이블 기본 콜레이션(`utf8mb4_0900_ai_ci`, 대소문자 무시)을 그대로 물려받았습니다.

**바뀐 이유**: 대소문자를 무시하는 콜레이션에서는 `Ab3f2Xz`와 `ab3f2xz`가 **같은 값**으로 취급됩니다. Base62로 랜덤 슬러그를 만들면 UNIQUE 충돌이 잦아지고, 실제 슬러그 조합 수가 62⁷에서 36⁷로 약 44.9배 줄어듭니다. 데이터가 없는 지금 고치면 무료지만, 데이터가 쌓인 뒤 콜레이션을 바꾸는 `ALTER`는 테이블 전체를 재작성해야 해서 비쌉니다.

**영향**: `Ab3f2Xz`와 `ab3f2xz`가 이제 서로 다른 슬러그로 취급됩니다(대소문자 구분).

---

## 4. `click_events.referrer_category` 컬럼 신규 추가

원본은 `referrer VARCHAR(500)` 하나만 있었고, Referer 헤더 원문을 그대로 저장하는 구조였습니다.

**바뀐 이유**: 원문 그대로는 통계를 낼 수 없습니다 — 같은 인스타그램이라도 `https://www.instagram.com/stories/abc123/?utm_source=ig`와 `https://l.instagram.com/?u=...`처럼 쿼리스트링·서브도메인이 매번 달라서, 원문 기준으로 집계하면 "인스타그램에서 온 사람"이 하나로 안 묶이고 URL마다 따로 카운트됩니다. 그래서 원문(`referrer`)은 디버깅용으로 그대로 두고, **수집 시점에 도메인 추출 → 카테고리 매핑(인스타그램/페이스북/검색엔진 등)한 값을 `referrer_category`에 별도 저장**하도록 컬럼을 추가했습니다. 조회할 때마다 매번 파싱하면 인덱스를 못 타고 느려지기 때문에, 반드시 저장 시점에 분류해야 합니다.

**영향**: 애플리케이션이 클릭 이벤트를 저장할 때 `referrer` 원문과 별개로 `referrer_category` 값(도메인→카테고리 매핑 결과, 없으면 `DIRECT`/매핑에 없으면 `ETC`)을 같이 채워야 합니다. 매핑 테이블이 나중에 바뀌면(예: 새 인스타그램 서브도메인 추가) 이미 저장된 과거 행의 `referrer_category`는 소급 갱신되지 않는다는 점도 함께 인지해야 합니다.

---

## 5. `click_events` 보관 기간: 365일 → 30일

원본: `COMMENT '클릭 원본 이벤트 로그 테이블 (365일 보관 후 삭제 대상)'`, 성능 최적화 섹션의 예시 `EVENT`도 `INTERVAL 365 DAY` 기준.

**바뀐 이유**: 초당 800클릭 요구사항 기준으로 계산하면 365일치가 약 252억 행, `user_agent`+`referrer` 컬럼까지 포함하면 수 TB가 되어 단일 RDS 인스턴스가 감당 못 합니다. 그래서 원본 로그는 **30일만 RDB에 두고, 365일 보관은 집계 테이블(`link_daily_stats`, `link_daily_dimension_stats`)이 대신 담당**하는 구조로 바꿨습니다. 30일 초과분은 S3로 export.

**영향**: 원본 클릭 로그로 30일보다 과거를 조회하는 기능은 불가능합니다(집계된 통계는 여전히 가능). 자동 삭제 배치는 아직 미구현 — 아래 8번 참조.

---

## 6. `idx_users_kakao_id` 인덱스 제거

원본은 `kakao_id BIGINT NOT NULL UNIQUE` + 별도 `CREATE INDEX idx_users_kakao_id ON users(kakao_id);`를 둘 다 갖고 있었습니다.

**바뀐 이유**: `UNIQUE` 제약은 MySQL에서 자동으로 인덱스를 만듭니다. 똑같은 컬럼에 인덱스를 두 벌 유지하는 셈이라, 조회 성능 이득은 없고 쓰기(INSERT/UPDATE) 비용만 늘어납니다. 순수 낭비라 제거했습니다.

**영향**: 없음. `kakao_id` 조회 성능은 UNIQUE 인덱스가 그대로 담당합니다.

---

## 7. FK 개수: 4개 → 3개 (`click_events` FK 제거)

> 원래 설계 문서는 4번 테이블(`link_daily_stats`)까지만 있던 3-테이블 구조를 기준으로 FK를 정의했습니다. 1번 항목에서 `link_daily_dimension_stats`를 추가하면서 이 테이블도 `links`를 참조하는 FK를 갖게 됐고, 그 결과 V1 시점 FK 총합은 4개입니다. 이 문서는 V1 이후 상태를 기준으로 서술합니다.

원본 설계(3-테이블 기준)는 FK 3개를 유지하는 구조였습니다:

```
users (1) ──< links (1) ──< click_events
                      └──< link_daily_stats
```

여기에 `link_daily_dimension_stats`가 추가되면서(1번 항목) `links`를 참조하는 FK가 하나 더 생겨, V1 적용 시점 기준 FK는 총 **4개**가 됩니다:

```
users (1) ──< links (1) ──< click_events
                      ├──< link_daily_stats
                      └──< link_daily_dimension_stats
```

**바뀐 이유**: 팀이 MSA로 가면서 "서비스 간 결합도 제거"를 방침으로 정했는데, FK 4개가 전부 서비스 경계(auth/link/stats)를 가로지르고 있었습니다.

논의 결과:

* `links`↔`users`, `link_daily_stats`↔`links`, `link_daily_dimension_stats`↔`links`는 **유지** —  
* `click_events`↔`links`만 **제거**했습니다(`V2__drop_click_events_fk.sql`) — 이 FK는 배포 방식과 무관하게, MySQL이 파티션된 InnoDB 테이블에 FK를 허용하지 않는다는 기술적 제약과 직결됩니다. 초당 800클릭 요구사항을 감당하려면 `click_events` 월별 파티셔닝이 필요해서, 이 FK만은 반드시 제거해야 했습니다.

제거 후 FK는 3개(`links`↔`users`, `link_daily_stats`↔`links`, `link_daily_dimension_stats`↔`links`)가 남습니다.

**영향**: 존재하지 않는 링크로 클릭 이벤트가 들어가는 것을 DB가 더 이상 막지 않습니다. 대신 리다이렉트 시점에 Redis 캐시로 링크 존재를 이미 확인하므로 실질적 위험은 낮고, 삭제된 링크의 클릭 로그가 고아로 남는지는 아래 쿼리로 주기 점검이 필요합니다. 다만 `links`는 하드 삭제 없이 `is_visible` 소프트 삭제만 쓰기로 했으므로, 현재 정책상 "링크가 실제로 사라져서 클릭 로그가 고아가 되는" 상황은 아직 발생하지 않습니다 — 이 위험은 향후 하드 삭제 기능이 생길 경우에 대비한 것입니다.

```sql
SELECT COUNT(*) FROM click_events e
  LEFT JOIN links l ON l.id = e.link_id WHERE l.id IS NULL;
```

---

## 8. `click_events` 파티셔닝 — PK 변경 + 월별 RANGE 파티션 적용 (`V3__partition_click_events.sql`)

원본 설계엔 파티셔닝 예시만 있고 실제로 필요한 선행조건은 검토돼 있지 않았습니다.

**이유**: MySQL 파티셔닝은 세 가지 조건을 모두 만족해야 합니다(①`DATETIME` 전환 ②FK 제거 ③PK에 파티션 기준 컬럼 포함). ①, ②는 `V1`, `V2`에서 먼저 해결했고, ③(PK를 `(id, clicked_at)` 복합키로 변경)과 실제 파티션 정의를 `V3`에서 마무리했습니다. `id`가 AUTO_INCREMENT라 그 자체로 이미 유일하므로, `clicked_at`이 PK에 추가돼도 단건 조회(`WHERE id = ?`)의 동작이나 실질적 유일성 판단 기준은 바뀌지 않습니다.

**영향**: `click_events`는 이제 `clicked_at` 기준 월별 RANGE 파티션 테이블입니다(현재 UTC 기준 2026-06 ~ 2026-11 + `p_future`를 미리 생성). 30일 초과 데이터 자동 삭제는 이 구조 위에서 `DROP PARTITION` 방식으로 구현할 예정이며, 아직 배치 자체는 미구현입니다(다음 달 파티션 자동 추가도 마찬가지로 별도 자동화 필요 — 현재는 수동으로 `REORGANIZE PARTITION`).

**자동화 방식**: 파티션 자동 생성(매달 다음 달 파티션 추가)과 30일 초과분 `DROP PARTITION` 삭제는 K8s CronJob으로 진행하기로 했습니다(`shortlink_migrator` 계정으로 접속해 `REORGANIZE PARTITION`/`DROP PARTITION`을 직접 실행). 검토했던 EventBridge+Lambda 대신 CronJob으로 정한 이유는, 서비스가 이미 K8s 위에서 운영되므로 별도의 서버리스 배포 파이프라인이나 IAM/VPC 연동을 새로 구성할 필요 없이 기존 클러스터의 Secret·네트워크 경로를 그대로 쓸 수 있기 때문입니다. 이 결정에 따라 `shortlink_migrator` 계정에는 MySQL 내장 이벤트 스케줄러(`CREATE EVENT`) 권한을 부여하지 않습니다 — `00-database-setup.sql`도 함께 갱신했습니다.

---

## 9. 방문자 식별 방식: IP+UA 해시 → 쿠키 UUID (`V4__click_events_cookie_visitor_id.sql`)

원본/V1 설계는 `visitor_hash CHAR(64)` — `SHA256(원본 IP + User-Agent)` 값으로 순 방문자(UV)를 판별했습니다.

**바뀐 이유**: IP+UA 해시 방식은 두 가지 문제가 있었습니다.
1. **역산 위험** — IP와 UA 조합의 경우의 수가 한정적이라 해시를 무차별 대입해 원본 IP를 복원할 수 있는 여지가 있었습니다.
2. **기간 UV 집계 불가** — 역산 위험을 낮추려고 salt를 일별로 로테이션하는 방식을 검토했는데, 그렇게 하면 같은 사람도 날짜가 바뀌면 해시값이 달라져 애초에 "하루 단위"를 벗어난 UV 집계가 불가능해집니다.

그래서 서버가 최초 방문 시 쿠키로 발급하는 순수 난수 UUID(`visitor_id`)로 전환했습니다. IP/UA 등 개인정보로부터 유도된 값이 아니라 역산 위험이 없고, salt 로테이션이 필요 없어 설계가 단순해집니다.

**영향**:
- `click_events.visitor_hash CHAR(64)` → `visitor_id CHAR(36)`로 컬럼명·타입이 바뀌었습니다(`CHANGE COLUMN`). 인덱스명도 `idx_click_events_visitor_hash` → `idx_click_events_visitor_id`로 변경.
- `CHAR(64)` → `CHAR(36)` 축소라, 마이그레이션 실행 전 `click_events`에 기존 행이 있으면(로컬 테스트 데이터 포함) `ERROR 1265: Data truncated`로 실패합니다. 기존 IP+UA 해시값은 새 UUID 체계와 호환되지 않으므로 실행 전 `TRUNCATE TABLE click_events;`로 비워야 합니다.
- 애플리케이션은 요청에 쿠키가 없으면(최초 방문) `visitor_id`를 새로 발급해 응답 `Set-Cookie`로 내려주는 동시에, 이번 클릭 로그의 값으로도 그대로 저장해야 누락이 없습니다.
- `visitor_hash`를 참조하던 배치·조회 쿼리(`02-stats-queries.sql`)와 엔티티(`ClickEvent`)도 함께 `visitor_id`로 갱신했습니다.
- 기간 순 방문자(UV) 집계 방식 자체는 바뀌지 않았습니다 — `link_daily_stats.visitor_count`는 여전히 "하루 단위"로만 유효하고, 기간 조회는 이 값을 그대로 합산해 "일별 방문자 합계"로 표기합니다(요약 표 참조). 진짜 기간 순방문자가 필요하면 30일 보관 한도 내에서 `click_events`를 직접 `COUNT(DISTINCT visitor_id)`해야 합니다.

---

## 요약 표

|구분|원본 설계|현재 구현|이유|
|-|-|-|-|
|테이블 수|4개|5개|차원 집계 테이블 추가|
|시간 컬럼|TIMESTAMP|DATETIME|2038년 한계 + 파티셔닝 선행조건|
|slug 콜레이션|테이블 기본값(대소문자 무시)|ascii_bin(대소문자 구분)|UNIQUE 충돌 방지|
|referrer 처리|원문만 저장|원문 + 분류값(category) 컬럼 분리|집계 가능하게 하려고|
|click_events 보관|365일|30일|용량(252억 행) 문제|
|idx_users_kakao_id|있음|제거|UNIQUE와 중복|
|FK 개수 (V1 시점 기준)|4개 (전부 유지)|3개 (click_events만 제거)|배포 동기화로 나머지는 유지 가능, click_events는 파티셔닝 때문에 필수 제거|
|click_events PK / 파티션 (V3)|id 단일 PK, 파티션 없음|(id, clicked_at) 복합 PK, clicked_at 월별 RANGE 파티션|초당 800클릭 감당 + 30일 초과분 DROP PARTITION 삭제 준비|
|방문자 식별 방식 (V4)|visitor_hash CHAR(64) = SHA256(IP+UA)|visitor_id CHAR(36) = 쿠키 발급 UUID|역산 위험 제거 + 기간 UV 집계 가능하게|

---

## 마이그레이션을 누가 실행하는가

이 폴더의 SQL을 **애플리케이션(Flyway)이 실행할지, 배포 파이프라인(`run-migration.sh`)이 실행할지** 를 먼저 정해야 합니다. 이 선택에 따라 `application.yml` 설정이 달라집니다.

### 권장: 파이프라인이 실행, 앱은 검증만

```yaml
spring:
  flyway:
    enabled: false          # 앱은 마이그레이션을 실행하지 않는다
  jpa:
    hibernate:
      ddl-auto: validate    # 스키마가 엔티티와 맞는지 확인만 한다
```

배포 시 `run-migration.sh`를 앱 기동 전 단계에서 한 번 돌립니다.

이유:

- **컨테이너 여러 개가 동시에 뜨는 환경(ECS/EKS)에서 안전합니다.** Flyway를 앱에 켜두면 태스크 N개가 동시에 부팅되며 같은 마이그레이션을 실행하려 듭니다. Flyway가 락으로 막아주긴 하지만, 나머지 태스크는 락을 기다리다 헬스체크 타임아웃에 걸릴 수 있습니다
- **앱 계정에 DDL 권한을 줄 필요가 없습니다.** `shortlink_app`은 DML만 갖고 DDL은 `shortlink_migrator`가 담당한다는 계정 분리가 유지됩니다. Flyway를 앱에서 돌리면 앱이 `ALTER`·`DROP` 권한을 갖게 되어 분리한 의미가 사라집니다
- **파티션 관리(`REORGANIZE PARTITION`), S3 export 같은 운영 작업과 실행 주체가 같아집니다**
- SQL이 `src/db/`에 있어도 경로 문제가 생기지 않습니다

### 대안: 앱에서 Flyway를 돌린다면

`spring.flyway.locations` 기본값은 `classpath:db/migration`입니다. 지금 SQL은 `src/db/`(리소스 밖)에 있으므로 그대로는 찾지 못합니다. 두 가지 방법이 있는데 **결과가 크게 다릅니다.**

**(a) 리소스로 복사 — 권장**

`build.gradle`에서 빌드 시 복사합니다.

```groovy
// management-service/build.gradle
tasks.register('copyMigrations', Copy) {
    from "$rootDir/src/db"
    include 'V*__*.sql'                       // 마이그레이션만. 00, 02, verify는 제외
    into "$buildDir/resources/main/db/migration"
}
processResources.dependsOn copyMigrations
```

`locations`를 건드릴 필요 없이 기본값 `classpath:db/migration`이 그대로 동작하고, **SQL이 jar 안에 포함되어 컨테이너에서도 문제없이 찾습니다.**

**(b) `filesystem:` 경로 — 로컬에서만 쓸 것**

```yaml
spring:
  flyway:
    locations: filesystem:../db
```

동작은 하지만 **운영 배포에서 깨집니다.**

- 상대 경로가 **실행 시점의 작업 디렉터리** 기준이라 IDE 실행, `./gradlew bootRun`, `java -jar` 가 각각 다르게 해석합니다
- Docker 이미지에는 보통 jar만 넣으므로 `src/db/` 자체가 컨테이너 안에 없습니다. `FlywayException: Unable to resolve location`으로 기동에 실패합니다

로컬 개발 편의로 쓰더라도 운영 프로파일에서는 (a)나 `enabled: false`로 바꿔야 합니다.

### 어느 쪽이든 지켜야 하는 것

- **`ddl-auto`는 항상 `validate`** — `create`/`update`는 파티션과 콜레이션 설정을 이해하지 못해 스키마를 망칩니다
- **마이그레이션 파일에는 DDL만** — `SHOW`/`SELECT`/`EXPLAIN` 같은 조회 구문은 `verify.sql`에 둡니다. 특히 `\G`는 mysql CLI 전용 종결자라 Flyway(JDBC)에서 문법 오류가 납니다
- **적용된 파일은 수정하지 않습니다** — 체크섬이 바뀌어 `validate`가 실패합니다. 항상 새 버전(V5, V6 ...)을 추가하세요

### 검증

마이그레이션 적용 후 아래로 확인합니다. 스키마를 바꾸지 않으므로 몇 번 실행해도 안전합니다.

```bash
mysql --defaults-extra-file=~/.my.cnf shortlink < verify.sql
```

확인 항목: 테이블 5개 / `click_events` PK가 `(id, clicked_at)`인지 / 파티션 목록과 프루닝 동작 / FK 잔여 여부 / `visitor_id` CHAR(36) / `slug` 콜레이션이 `ascii_bin`인지 / 서버 타임존이 UTC인지



