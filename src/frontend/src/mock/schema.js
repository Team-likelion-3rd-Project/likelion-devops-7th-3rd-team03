// 6.4 데이터 모델 명세를 그대로 반영한 상수 정의.
// short_code 컬럼은 CHARACTER SET ascii COLLATE ascii_bin 이므로
// JS 문자열 비교(===)를 그대로 쓰면 대소문자 구분이 동일하게 재현된다.

export const BASE62_CHARS =
  'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'

export const SHORT_CODE_LENGTH = 7
export const CUSTOM_ALIAS_MIN = 4
export const CUSTOM_ALIAS_MAX = 20
export const CUSTOM_ALIAS_PATTERN = /^[A-Za-z0-9_-]+$/

// 문서 6.3.2 예약어 차단 목록
export const RESERVED_CODES = new Set([
  'api',
  'admin',
  'login',
  'health',
  'static',
  'favicon',
  'robots',
  'app',
  'www',
  'dashboard',
  'collections',
  'stats',
  'architecture',
])

export const URL_SCHEME_PATTERN = /^https?:\/\//i

export const MAX_CUSTOM_ALIASES_PER_USER = 20

// 리다이렉트 캐시 시뮬레이션 (ElastiCache 대체) TTL - ms 단위, 문서 6.6 참조 (3600s를 데모용으로 축소)
export const CACHE_TTL_MS = 15_000
export const NEGATIVE_CACHE_TTL_MS = 5_000

// SQS -> Lambda 배치 컨슈머가 폴링하는 주기 시뮬레이션 (문서 아키텍처 8~11단계)
export const BATCH_AGGREGATION_DELAY_MS = 2_500
