// Load/Stress 겸용 스크립트 — PEAK_TPS 환경 변수 하나로 두 시나리오 재사용 가능
// (예: Load는 PEAK_TPS=23, Stress는 PEAK_TPS=230).
//
// [테스트 시나리오: 총 20분]
// 0 → PEAK_TPS로 5분 상승 → 10분 유지 → 5분 하강
//
// [트래픽 분배: 80/20 & Coupon Collector 최적화]
// 링크는 하나의 풀(1..SLUG_COUNT)로 생성하며, 그 안에서 앞쪽 HOT_COUNT개(비율이 아닌 절대값)를
// 인기 링크 구간으로 취급하고 트래픽의 80%(HOT_TRAFFIC_RATIO)를 집중시킨다.
// * HOT_COUNT를 작게 유지하는 이유: 핫 구간이 너무 크면 테스트 시간 내내 캐시에 다 올라가지 못해
//   DB 부하만 지속되므로, 실제 "소수 인기 링크" 환경을 모사하고 빠른 웜업을 유도하기 위함.
//
// [세션 고정 및 검증]
// VU마다 User-Agent/Referer/visitor_id(UUIDv4) 조합을 고정하여 실제 세션처럼 행동하게 함.
// 테스트 격리를 위해 링크 ID로 트래픽을 필터링하며, 이후 Athena에서 visitor_id를 기준으로
// UA 파싱(yauaa) 및 Referrer 분류가 정확하게 동작했는지 대조 및 검증 가능.
//
// 사용법:
//   BASE_URL=http://redirect-service:8080 SLUG_PREFIX=lt123456 SLUG_COUNT=300000 \
//     PEAK_TPS=230 k6 run load-test/normal-traffic.js

import http from "k6/http";
import { check } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://redirect-service:8080";
const SLUG_PREFIX = __ENV.SLUG_PREFIX || "test";
const SLUG_COUNT = Number(__ENV.SLUG_COUNT || 300000);
const PEAK_TPS = Number(__ENV.PEAK_TPS || 200);

const HOT_COUNT = Math.min(SLUG_COUNT, Number(__ENV.HOT_COUNT || 50));
const HOT_TRAFFIC_RATIO = Number(__ENV.HOT_TRAFFIC_RATIO || 0.8);
const TESTID = __ENV.TESTID || "unknown";

export function setup() {
  console.log(`[testid] ${TESTID}`);
}

// device_type(yauaa DeviceClass)별로 하나씩 — DESKTOP/MOBILE/TABLET 커버
const UA_PROFILES = [
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36", // DESKTOP/Windows/Chrome
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15", // DESKTOP/macOS/Safari
  "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1", // MOBILE/iOS/Safari
  "Mozilla/5.0 (Linux; Android 14; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36", // MOBILE/Android/Chrome
  "Mozilla/5.0 (iPad; CPU OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1", // TABLET/iPadOS/Safari
];

// ReferrerCategoryResolver 매칭 규칙 하나씩 커버 (null은 DIRECT)
const REFERRER_PROFILES = [
  "https://www.instagram.com/",
  "https://www.facebook.com/",
  "https://www.naver.com/",
  "https://www.google.com/search?q=snipy",
  "https://talk.kakao.com/",
  "https://t.co/abc123",
  null,
  "https://random-blog-example.net/post/1",
];

function uuidv4() {
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

// VU마다 한 번만 배정하고 이터레이션 동안 재사용
const visitorProfiles = {};
function getVisitorProfile() {
  const vu = __VU;
  if (!visitorProfiles[vu]) {
    visitorProfiles[vu] = {
      visitorId: uuidv4(),
      userAgent: UA_PROFILES[vu % UA_PROFILES.length],
      referrer: REFERRER_PROFILES[vu % REFERRER_PROFILES.length],
    };
    // 로깅 부하 방지를 위해 디버깅 용도가 아니면 주석 처리 권장
    // console.log(`visitor VU=${vu} visitor_id=${visitorProfiles[vu].visitorId}`);
  }
  return visitorProfiles[vu];
}

export const options = {
  tags: { testid: TESTID }, // 모든 메트릭에 testid 라벨을 붙여 Grafana/Prometheus에서 이번 실행만 필터링 가능하게 함
  scenarios: {
    ramped_load: {
      executor: "ramping-arrival-rate",
      startRate: 0,
      timeUnit: "1s",
      preAllocatedVUs: 50,
      maxVUs: 1000, // 응답 지연 시 TPS 유지를 위한 넉넉한 예비 VU
      stages: [
        { target: PEAK_TPS, duration: "5m" },  // 5분 상승
        { target: PEAK_TPS, duration: "10m" }, // 10분 유지
        { target: 0, duration: "5m" },         // 5분 하강 (총 20분)
      ],
    },
  },
  thresholds: {
    http_req_failed: ["rate==0"],
    http_req_duration: ["p(95)<150", "p(99)<1000"],
  },
};

export default function () {
  let n, pool;

  // 경계값 처리: HOT_COUNT가 전체 풀과 같거나 클 경우 에러 방지
  if (HOT_COUNT >= SLUG_COUNT || Math.random() < HOT_TRAFFIC_RATIO) {
    n = 1 + Math.floor(Math.random() * HOT_COUNT); // 핫 구간
    pool = "hot";
  } else {
    n = HOT_COUNT + 1 + Math.floor(Math.random() * (SLUG_COUNT - HOT_COUNT)); // 콜드 구간
    pool = "cold";
  }

  const slug = `${SLUG_PREFIX}${n}`;
  const visitor = getVisitorProfile();

  const headers = {
    "User-Agent": visitor.userAgent,
    Cookie: `visitor_id=${visitor.visitorId}`,
  };
  if (visitor.referrer) {
    headers["Referer"] = visitor.referrer;
  }

  // tags.name으로 URL 변동에 따른 메트릭 카디널리티 폭발 방지
  const res = http.get(`${BASE_URL}/${slug}`, {
    redirects: 0,
    headers,
    tags: { name: `redirect_${pool}` },
  });

  check(res, {
    "status is 302 or 404": (r) => r.status === 302 || r.status === 404,
  });
}