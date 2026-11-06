// "Warmup 없는 바이럴 링크" 시나리오. 가설: 캐시 미스 herd가 MySQL/HikariCP에
// 병목을 만든다 — RedirectService.findDatabaseRedirectTarget에 동시성 보호가
// 없어서(락/synchronized/분산락 없음) 그대로 DB까지 전달됨.
//
// 사용법:
//   BASE_URL=http://redirect-service:8080 SLUGS=cold-test-1 k6 run load-test/viral-spike.js
//
// SLUGS는 콤마로 여러 개 가능(herd 확대용). 테스트 전 Redis에 없는 상태여야 herd가
// 재현된다. BASE_URL 기본값이 내부 DNS인 이유는 load-test/README.md 참고(WAF 403).
// SPIKE_TPS는 normal-traffic.js의 평소 피크(PEAK_TPS 기본 800)보다 확실히 높게 잡아야
// "바이럴 스파이크"의 의미가 있다 — 기본값 1600은 평소 피크의 정확히 2배.
import http from "k6/http";
import { check } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://redirect-service:8080";
const SLUGS = (__ENV.SLUGS || __ENV.SLUG || "test").split(",").map((s) => s.trim());
const SPIKE_TPS = Number(__ENV.SPIKE_TPS || 1600);
const TESTID = __ENV.TESTID || "unknown";

export function setup() {
  console.log(`[testid] ${TESTID}`);
}

export const options = {
  tags: { testid: TESTID }, // 모든 메트릭에 testid 라벨을 붙여 Grafana/Prometheus에서 이번 실행만 필터링 가능하게 함
  scenarios: {
    viral_spike: {
      executor: "ramping-arrival-rate",
      startRate: 0,
      timeUnit: "1s",
      preAllocatedVUs: 400,
      maxVUs: 1000, // SPIKE_TPS 2배 인상에 맞춰 같이 올림 — 응답 지연 시에도 목표 TPS를 유지하기 위한 예비 VU
      stages: [
        { target: SPIKE_TPS, duration: "10s" }, // 바이럴 — 급격한 유입
        { target: SPIKE_TPS, duration: "4m30s" }, // 유지
        { target: 0, duration: "20s" }, // 정리
      ],
    },
  },
  thresholds: {
    http_req_failed: ["rate<=0"],
    http_req_duration: ["p(95)<150"],
  },
};

export default function () {
  const slug = SLUGS[Math.floor(Math.random() * SLUGS.length)];
  // tags.name 고정 — 안 주면 k6가 URL(슬러그별로 다름)을 라벨로 써서 Prometheus
  // 시계열이 슬러그 개수만큼 폭발한다 (실제로 87만 개까지 간 적 있음).
  const res = http.get(`${BASE_URL}/${slug}`, { redirects: 0, tags: { name: "redirect" } });

  check(res, {
    "status is 302 or 404": (r) => r.status === 302 || r.status === 404,
  });
}
