// localStorage를 MySQL 테이블처럼 사용하는 아주 얇은 "가짜 DB" 레이어.
// 문서 6.4의 테이블 구조를 그대로 옮겨서, 실제 백엔드가 생기면
// src/mock/api.js 하나만 실제 fetch 호출로 바꿔치기하면 되도록 만들었다.

const DB_KEY = 'linkshortener_db_v1'

const EMPTY_DB = () => ({
  meta: { autoIncrement: {} },
  users: [],
  oauth_accounts: [],
  links: [],
  click_events: [],
  link_daily_stats: [],
})

function load() {
  const raw = localStorage.getItem(DB_KEY)
  if (!raw) return EMPTY_DB()
  try {
    const parsed = JSON.parse(raw)
    return { ...EMPTY_DB(), ...parsed }
  } catch {
    return EMPTY_DB()
  }
}

function save(db) {
  localStorage.setItem(DB_KEY, JSON.stringify(db))
}

// links.id / click_events.id 처럼 BIGINT AUTO_INCREMENT 컬럼을 흉내낸다.
export function nextId(db, table) {
  const current = db.meta.autoIncrement[table] || 0
  const next = current + 1
  db.meta.autoIncrement[table] = next
  return next
}

export function withDb(fn) {
  const db = load()
  const result = fn(db)
  save(db)
  return result
}

export function readDb(fn) {
  const db = load()
  return fn(db)
}

export function resetDb() {
  localStorage.removeItem(DB_KEY)
}

export function nowIso() {
  return new Date().toISOString()
}
