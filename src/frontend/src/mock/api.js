// "가짜 백엔드" - 실제로는 Spring Boot(EC2) + RDS MySQL + ElastiCache + SQS/Lambda가
// 할 일을 브라우저 안에서 흉내낸다. 각 함수의 주석에 문서의 어느 섹션/아키텍처
// 단계에 대응하는지 적어뒀다. 나중에 실제 API 서버가 생기면 이 파일의 함수
// 시그니처만 그대로 유지한 채 내부 구현을 fetch(...) 호출로 바꾸면 된다.

import { withDb, readDb, nextId, nowIso } from './db'
import {
  BASE62_CHARS,
  SHORT_CODE_LENGTH,
  CUSTOM_ALIAS_MIN,
  CUSTOM_ALIAS_MAX,
  CUSTOM_ALIAS_PATTERN,
  RESERVED_CODES,
  URL_SCHEME_PATTERN,
  MAX_CUSTOM_ALIASES_PER_USER,
  CACHE_TTL_MS,
  NEGATIVE_CACHE_TTL_MS,
  BATCH_AGGREGATION_DELAY_MS,
} from './schema'

// ---------------------------------------------------------------------------
// 네트워크 왕복 시뮬레이션 (ALB -> EC2 -> RDS Proxy 하고도 비슷한 체감 지연)
// ---------------------------------------------------------------------------
const NOT_FOUND = Symbol('NOT_FOUND')
const delay = (ms = 220 + Math.random() * 180) => new Promise((r) => setTimeout(r, ms))

class ApiError extends Error {
  constructor(status, message) {
    super(message)
    this.status = status
  }
}

// ---------------------------------------------------------------------------
// ElastiCache(Redis) 대체 - 실제 서비스가 아니라 모듈 스코프 Map이라 새로고침하면
// 사라진다. 문서 6.6의 "link:v1:{short_code}" 키 설계를 그대로 옮겼다.
// ---------------------------------------------------------------------------
const redirectCache = new Map() // key -> { value, expiresAt, isNegative }

function cacheGet(shortCode) {
  const key = `link:v1:${shortCode}`
  const entry = redirectCache.get(key)
  if (!entry) return { hit: false }
  if (Date.now() > entry.expiresAt) {
    redirectCache.delete(key)
    return { hit: false }
  }
  return { hit: true, value: entry.value, isNegative: entry.isNegative }
}

function cacheSet(shortCode, value) {
  redirectCache.set(`link:v1:${shortCode}`, {
    value,
    isNegative: false,
    expiresAt: Date.now() + CACHE_TTL_MS,
  })
}

function cacheSetNegative(shortCode) {
  redirectCache.set(`link:v1:${shortCode}`, {
    value: '__NOT_FOUND__',
    isNegative: true,
    expiresAt: Date.now() + NEGATIVE_CACHE_TTL_MS,
  })
}

export function cacheInvalidate(shortCode) {
  redirectCache.delete(`link:v1:${shortCode}`)
}

// ---------------------------------------------------------------------------
// Auth - 카카오 OAuth 대신 닉네임만으로 즉시 로그인되는 목업.
// 세션은 sessionStorage에 user_id만 저장 (JWT/Redis 세션 대체)
// ---------------------------------------------------------------------------
const SESSION_KEY = 'linkshortener_session_v1'

export async function mockKakaoLogin(nickname) {
  await delay()
  return withDb((db) => {
    let user = db.users.find((u) => u.nickname === nickname && !u.deleted_at)
    if (!user) {
      user = {
        id: nextId(db, 'users'),
        nickname,
        email: null,
        status: 'ACTIVE',
        created_at: nowIso(),
        updated_at: nowIso(),
        deleted_at: null,
      }
      db.users.push(user)
      db.oauth_accounts.push({
        id: nextId(db, 'oauth_accounts'),
        user_id: user.id,
        provider: 'KAKAO',
        provider_user_id: `kakao_${user.id}_${Date.now()}`,
        connected_at: nowIso(),
      })
    }
    sessionStorage.setItem(SESSION_KEY, String(user.id))
    return user
  })
}

export function logout() {
  sessionStorage.removeItem(SESSION_KEY)
}

export function getCurrentUser() {
  const id = Number(sessionStorage.getItem(SESSION_KEY))
  if (!id) return null
  return readDb((db) => db.users.find((u) => u.id === id && !u.deleted_at) || null)
}

// ---------------------------------------------------------------------------
// 단축코드 생성 - 6.3.2 랜덤 Base62(7) + UNIQUE + 충돌 시 재시도
// ---------------------------------------------------------------------------
function randomBase62(length) {
  const bytes = new Uint32Array(length)
  crypto.getRandomValues(bytes)
  let out = ''
  for (let i = 0; i < length; i++) {
    out += BASE62_CHARS[bytes[i] % BASE62_CHARS.length]
  }
  return out
}

function codeExists(db, code) {
  // short_code UNIQUE(ascii_bin) -> 대소문자 구분 비교. JS === 는 이미 그렇게 동작한다.
  return db.links.some((l) => l.short_code === code)
}

function generateUniqueRandomCode(db) {
  for (let attempt = 0; attempt < 5; attempt++) {
    const candidate = randomBase62(SHORT_CODE_LENGTH)
    if (RESERVED_CODES.has(candidate.toLowerCase())) continue
    if (!codeExists(db, candidate)) return candidate
  }
  throw new ApiError(500, '단축코드 생성에 반복적으로 실패했습니다 (키 공간 고갈은 사실상 불가능한 확률이므로 일시적 오류일 가능성이 높습니다).')
}

function validateCustomAlias(db, alias) {
  if (alias.length < CUSTOM_ALIAS_MIN || alias.length > CUSTOM_ALIAS_MAX) {
    throw new ApiError(400, `커스텀 별칭은 ${CUSTOM_ALIAS_MIN}~${CUSTOM_ALIAS_MAX}자여야 합니다.`)
  }
  if (!CUSTOM_ALIAS_PATTERN.test(alias)) {
    throw new ApiError(400, '커스텀 별칭은 영문/숫자/-/_ 만 사용할 수 있습니다.')
  }
  if (RESERVED_CODES.has(alias.toLowerCase())) {
    throw new ApiError(400, `'${alias}'는 예약어라 사용할 수 없습니다.`)
  }
  if (codeExists(db, alias)) {
    throw new ApiError(409, '이미 사용 중인 별칭입니다.')
  }
}

// ---------------------------------------------------------------------------
// links
// ---------------------------------------------------------------------------
export async function createLink({ userId, originalUrl, customAlias, title, expiresAt }) {
  await delay()
  if (!URL_SCHEME_PATTERN.test(originalUrl)) {
    throw new ApiError(400, 'http:// 또는 https:// 로 시작하는 URL만 입력할 수 있습니다. (ck_links_url_scheme)')
  }
  if (originalUrl.length > 2048) {
    throw new ApiError(400, 'URL은 2048자를 넘을 수 없습니다.')
  }

  return withDb((db) => {
    const isCustom = Boolean(customAlias)
    let code

    if (isCustom) {
      const ownedCustomCount = db.links.filter(
        (l) => l.user_id === userId && l.is_custom && !l.deleted_at
      ).length
      if (ownedCustomCount >= MAX_CUSTOM_ALIASES_PER_USER) {
        throw new ApiError(400, `커스텀 별칭은 사용자당 ${MAX_CUSTOM_ALIASES_PER_USER}개까지 만들 수 있습니다.`)
      }
      validateCustomAlias(db, customAlias)
      code = customAlias
    } else {
      // INSERT 후 DuplicateKeyException 캐치 + 재시도 패턴을 흉내낸다 (문서 6.3.2)
      code = generateUniqueRandomCode(db)
    }

    const link = {
      id: nextId(db, 'links'),
      short_code: code,
      original_url: originalUrl,
      user_id: userId,
      title: title || null,
      is_custom: isCustom ? 1 : 0,
      is_active: 1,
      expires_at: expiresAt || null,
      total_click_count: 0,
      last_clicked_at: null,
      created_at: nowIso(),
      updated_at: nowIso(),
      deleted_at: null,
    }
    db.links.push(link)
    return link
  })
}

export async function listLinksByUser(userId) {
  await delay()
  return readDb((db) =>
    db.links
      .filter((l) => l.user_id === userId && !l.deleted_at)
      .sort((a, b) => new Date(b.created_at) - new Date(a.created_at))
  )
}

export async function toggleActive(linkId, userId) {
  await delay(120)
  return withDb((db) => {
    const link = db.links.find((l) => l.id === linkId && l.user_id === userId)
    if (!link) throw new ApiError(404, '링크를 찾을 수 없습니다.')
    link.is_active = link.is_active ? 0 : 1
    link.updated_at = nowIso()
    cacheInvalidate(link.short_code)
    return link
  })
}

export async function softDeleteLink(linkId, userId) {
  await delay(120)
  return withDb((db) => {
    const link = db.links.find((l) => l.id === linkId && l.user_id === userId)
    if (!link) throw new ApiError(404, '링크를 찾을 수 없습니다.')
    // 소프트 삭제 - short_code는 영구 점유되어 재발급되지 않는다 (피싱 벡터 방지, 6.4.3)
    link.deleted_at = nowIso()
    cacheInvalidate(link.short_code)
    return link
  })
}

// 리다이렉트 조회 - 문서 6.7 "리다이렉트 (캐시 미스 시)" 쿼리 + 6.6 캐시 설계를 합친 것.
// trace를 반환해서 화면에서 "CloudFront -> ALB -> EC2 -> ElastiCache -> RDS" 중
// 어디까지 갔는지 보여줄 수 있게 했다.
export async function resolveShortCode(shortCode) {
  const trace = ['CloudFront (엣지 캐시 미스 가정)', 'ALB → EC2 (App)']
  await delay(150)

  const cached = cacheGet(shortCode)
  if (cached.hit) {
    trace.push('ElastiCache HIT')
    await delay(30)
    if (cached.isNegative) {
      return { status: 404, trace }
    }
    const link = readDb((db) => db.links.find((l) => l.short_code === shortCode))
    return evaluateLink(link, trace)
  }

  trace.push('ElastiCache MISS')
  await delay(180)
  trace.push('RDS Proxy → RDS MySQL (short_code UNIQUE 조회)')

  const link = readDb((db) =>
    db.links.find((l) => l.short_code === shortCode && !l.deleted_at)
  )

  if (!link) {
    cacheSetNegative(shortCode)
    return { status: 404, trace }
  }

  cacheSet(shortCode, link.original_url)
  return evaluateLink(link, trace)
}

function evaluateLink(link, trace) {
  if (!link || link.deleted_at) return { status: 404, trace }
  if (!link.is_active) return { status: 410, trace, reason: 'INACTIVE' }
  if (link.expires_at && new Date(link.expires_at).getTime() < Date.now()) {
    return { status: 410, trace, reason: 'EXPIRED' }
  }
  return { status: 200, trace, link }
}

// 클릭 기록 - 아키텍처 8~11단계 (EC2 -> SQS -> Lambda 컨슈머 -> 배치 UPSERT) 시뮬레이션.
// click_events에는 즉시 적재하고, links.total_click_count / link_daily_stats는
// 실제로도 그렇듯 약간의 지연 후에야 반영된다 (쓰기 경합 회피, 문서 6.1 원칙).
export async function recordClick(shortCode, meta = {}) {
  const linkId = readDb((db) => db.links.find((l) => l.short_code === shortCode)?.id)
  if (!linkId) return

  withDb((db) => {
    db.click_events.push({
      id: nextId(db, 'click_events'),
      link_id: linkId,
      occurred_at: nowIso(),
      ip_hash: meta.ipHash || 'sha256:demo-hash',
      referer: meta.referer || document.referrer || null,
      user_agent: navigator.userAgent,
      device_type: /Mobi/i.test(navigator.userAgent) ? 'MOBILE' : 'DESKTOP',
      country: 'KR',
    })
  })

  setTimeout(() => {
    withDb((db) => {
      const link = db.links.find((l) => l.id === linkId)
      if (!link) return
      const today = new Date().toISOString().slice(0, 10)
      let stat = db.link_daily_stats.find(
        (s) => s.link_id === linkId && s.stat_date === today
      )
      if (!stat) {
        stat = {
          link_id: linkId,
          stat_date: today,
          click_count: 0,
          unique_count: 0,
          updated_at: nowIso(),
        }
        db.link_daily_stats.push(stat)
      }
      stat.click_count += 1
      // ip_hash DISTINCT 근사치 데모 (미결정사항 #2) - 실제로는 HLL/DISTINCT 집계
      stat.unique_count = Math.max(stat.unique_count, Math.round(stat.click_count * 0.8))
      stat.updated_at = nowIso()

      link.total_click_count += 1
      link.last_clicked_at = nowIso()
      link.updated_at = nowIso()
    })
    window.dispatchEvent(new CustomEvent('linkshortener:stats-updated', { detail: { linkId } }))
  }, BATCH_AGGREGATION_DELAY_MS)
}

export async function getLinkById(linkId, userId) {
  await delay(120)
  return readDb((db) => db.links.find((l) => l.id === linkId && l.user_id === userId) || null)
}

// ---------------------------------------------------------------------------
// stats - 문서 6.7 "통계 조회" (link_daily_stats에서만 읽고 원시 이벤트는 집계하지 않음)
// ---------------------------------------------------------------------------
export async function dailyStatsForLink(linkId, days = 14) {
  await delay(150)
  return readDb((db) => {
    const rows = db.link_daily_stats.filter((s) => s.link_id === linkId)
    const byDate = new Map(rows.map((r) => [r.stat_date, r]))
    const out = []
    for (let i = days - 1; i >= 0; i--) {
      const d = new Date()
      d.setDate(d.getDate() - i)
      const key = d.toISOString().slice(0, 10)
      const row = byDate.get(key)
      out.push({
        stat_date: key,
        click_count: row?.click_count || 0,
        unique_count: row?.unique_count || 0,
      })
    }
    return out
  })
}

export async function recentClickEvents(linkId, limit = 10) {
  await delay(120)
  return readDb((db) =>
    db.click_events
      .filter((c) => c.link_id === linkId)
      .sort((a, b) => new Date(b.occurred_at) - new Date(a.occurred_at))
      .slice(0, limit)
  )
}

export { ApiError }
