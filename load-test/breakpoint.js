// 단일 파드 순수 처리량 한계 측정용 breakpoint test.
// TPS를 MAX_TPS까지 RAMP_DURATION에 걸쳐 서서히 올리다가, 에러율/latency가
// "명백히 깨진" 수준을 넘으면 k6가 스스로 중단한다(abortOnFail) — 멈춘 시점의
// 실제 TPS가 곧 한계치다. Load/Stress 테스트의 엄격한 SLA(p99<150ms, 에러율 0%)와
// 달리, 여기 threshold는 일부러 느슨하다(어디까지 버티는지 보는 게 목적이지
// SLA 충족 여부를 보는 게 아님).
//
// 단일 파드로 격리하는 법: Service(redirect-service:8080)로 쏘면 여러 파드에
// 로드밸런싱되어 "파드 1개 한계"를 못 잰다. HPA/Deployment를 직접 건드리면
// ArgoCD selfHeal이 되돌려버리므로(실제로 겪음), breakpoint-run.sh가 파드 하나를
// 골라 그 Pod IP로 직접 쏜다 — Service를 안 거치니 그 파드 하나만 받는다.
//
// 링크 풀을 작게(기본 20개) 잡는 이유: 이건 캐시 미스/DB 부하를 보는 테스트가
// 아니라 "캐시 히트 경로에서 파드가 순수하게 얼마나 처리하는지"를 보는 테스트라,
// 풀이 작아서 초반에 다 캐싱되고 이후로는 순수 요청 처리 능력만 측정된다.
//
// 사용법:
//   BASE_URL=http://10.0.11.84:8080 SLUG_PREFIX=bp123456 SLUG_COUNT=20 \
//     MAX_TPS=2000 RAMP_DURATION=10m k6 run load-test/breakpoint.js
import http from "k6/http";
import { check } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://redirect-service:8080";
const SLUG_PREFIX = __ENV.SLUG_PREFIX || "test";
const SLUG_COUNT = Number(__ENV.SLUG_COUNT || 20);
const MAX_TPS = Number(__ENV.MAX_TPS || 2000);
const RAMP_DURATION = __ENV.RAMP_DURATION || "10m";
const TESTID = __ENV.TESTID || "unknown";

export function setup() {
  console.log(`[testid] ${TESTID}`);
}

export const options = {
  tags: { testid: TESTID }, // 모든 메트릭에 testid 라벨을 붙여 Grafana/Prometheus에서 이번 실행만 필터링 가능하게 함
  scenarios: {
    breakpoint: {
      executor: "ramping-arrival-rate",
      startRate: 0,
      timeUnit: "1s",
      preAllocatedVUs: 50,
      maxVUs: 2000,
      stages: [{ target: MAX_TPS, duration: RAMP_DURATION }],
    },
  },
  thresholds: {
    // delayAbortEval: 순간적인 blip 하나로 조기 중단되지 않도록 15초 유예.
    http_req_failed: [{ threshold: "rate<0.05", abortOnFail: true, delayAbortEval: "15s" }],
    http_req_duration: [
      { threshold: "p(95)<1000", abortOnFail: true, delayAbortEval: "15s" },
    ],
  },
};

export default function () {
  const n = 1 + Math.floor(Math.random() * SLUG_COUNT);
  const slug = `${SLUG_PREFIX}${n}`;

  // tags.name 고정 — 카디널리티 폭발 방지 (다른 스크립트와 동일한 이유).
  const res = http.get(`${BASE_URL}/${slug}`, { redirects: 0, tags: { name: "redirect" } });

  check(res, {
    "status is 302 or 404": (r) => r.status === 302 || r.status === 404,
  });
}
