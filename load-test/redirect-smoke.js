// 아주 간단한 redirect-service 부하테스트.
//
// 사용법:
//   BASE_URL=https://dev.snipy.life SLUG=abcd1234 k6 run load-test/redirect-smoke.js
//
// SLUG은 실제로 links 테이블에 등록된 slug로 넘겨야 302를 받는다.
// (없으면 404 응답을 대상으로 서버가 부하 상황에서 얼마나 버티는지만 확인하게 됨)

import http from "k6/http";
import { check, sleep } from "k6";

const BASE_URL = __ENV.BASE_URL || "https://dev.snipy.life";
const SLUG = __ENV.SLUG || "test";
const TESTID = __ENV.TESTID || "unknown";

export const options = {
  vus: Number(__ENV.VUS || 10),
  duration: __ENV.DURATION || "30s",
  tags: { testid: TESTID }, // 모든 메트릭에 testid 라벨을 붙여 Grafana/Prometheus에서 이번 실행만 필터링 가능하게 함
};

export function setup() {
  console.log(`[testid] ${TESTID}`);
}

export default function () {
  // tags.name 고정 — 안 주면 k6가 URL(슬러그별로 다 다름)을 그대로 메트릭 라벨로 써서
  // Prometheus 시계열이 slug 개수만큼 폭발한다 (실제로 87만 개까지 간 적 있음).
  const res = http.get(`${BASE_URL}/${SLUG}`, { redirects: 0, tags: { name: "redirect" } });

  check(res, {
    "status is 302 or 404": (r) => r.status === 302 || r.status === 404,
  });

  sleep(1);
}
