// 링크 API는 모두 선택한 워크스페이스 경로 아래에 있다(/api/workspaces/{id}/links[/{linkId}], #204).
// userId는 JWT에서 서버가 추출한다.
//
// 워크스페이스 API를 mock으로 쓰는 개발 모드(MOCK_TEAMS)에서는 링크도 localStorage mock으로 동작한다.
// VITE_WORKSPACE_API=real 이면 모든 워크스페이스가 실제 API를 쓴다.
import { apiFetch, ApiError } from './http'
import { MOCK_TEAMS, mockAssertMember, mockMe } from './workspaces'

// 목록 항목의 만든 사람은 createdBy { userId, nickname, profileImageUrl }이다(회원 정보가 없으면 null).
const toLink = (l) => ({ ...l, creator: l.createdBy?.nickname ?? '' })

const MOCK_ID = 'mlink-'
const linkPath = (ws) => `/workspaces/${ws.workspaceId}/links`

export async function listLinks(ws, page = 0, size = 20) {
  if (MOCK_TEAMS) return mock.list(ws.workspaceId, page, size)
  const params = new URLSearchParams({ page, size })
  const { data } = await apiFetch(`${linkPath(ws)}?${params}`)
  return { ...data, links: data.links.map(toLink) }
}

export async function createLink(ws, { originalUrl, title, expiresAt }) {
  if (MOCK_TEAMS) return mock.create(ws.workspaceId, { originalUrl, title, expiresAt })
  const res = await apiFetch(linkPath(ws), {
    method: 'POST',
    body: { originalUrl, title, expiresAt }
  })
  return res.data
}

export async function updateLink(ws, linkId, { originalUrl, title, expiresAt }) {
  if (MOCK_TEAMS) return mock.update(linkId, { originalUrl, title, expiresAt })
  const res = await apiFetch(`${linkPath(ws)}/${linkId}`, {
    method: 'PATCH',
    body: { originalUrl, title, expiresAt }
  })
  return res.data
}

export async function deleteLink(ws, linkId) {
  if (MOCK_TEAMS) return mock.remove(linkId)
  await apiFetch(`${linkPath(ws)}/${linkId}`, { method: 'DELETE' })
}

// ---- mock: 같은 팀 멤버는 서로의 링크를 보고 고치고 지울 수 있고, 다른 팀 링크는 403이다. ----
// 팀당 개수 제한 값은 백엔드에서 정해지지 않았다(#204 열린 질문). 안내 문구를 시험하려고 작게 둔다.

export const MOCK_TEAM_LINK_LIMIT = 10
const STORE_KEY = 'snipy_mock_links'

const daysAgo = (n) => new Date(Date.now() - n * 86_400_000).toISOString()
const daysAhead = (n) => new Date(Date.now() + n * 86_400_000).toISOString()

function seed() {
  const l = (n, workspaceId, creator, title, url, created, expires) => ({
    linkId: `${MOCK_ID}seed-${n}`,
    workspaceId,
    shortUrl: `https://snipy.test/s${n}`,
    originalUrl: url,
    title,
    creator,
    createdAt: daysAgo(created),
    expiresAt: expires
  })
  return {
    links: [
      l(
        1,
        'ws-marketing',
        '테스트 관리자',
        '봄 캠페인 랜딩',
        'https://example.com/spring',
        20,
        daysAhead(40)
      ),
      l(
        2,
        'ws-marketing',
        '박서연',
        '뉴스레터 11월호',
        'https://example.com/newsletter/11',
        9,
        daysAhead(20)
      ),
      l(
        3,
        'ws-marketing',
        '이도윤',
        '지난 이벤트',
        'https://example.com/event-old',
        60,
        daysAgo(5)
      ),
      l(
        4,
        'ws-events',
        '이도윤',
        '현장 등록 폼',
        'https://example.com/register',
        12,
        daysAhead(90)
      ),
      l(
        5,
        'ws-design',
        '박서연',
        '디자인 시스템 문서',
        'https://example.com/design-system',
        30,
        daysAhead(120)
      ),
      l(6, 'ws-design', '최하은', '브랜드 가이드', 'https://example.com/brand', 3, daysAhead(60))
    ]
  }
}

// 부를 때마다 저장소에서 새로 읽는다(탭끼리 공유). 그래서 한 함수 안에서는 읽은 db 하나만 고쳐 저장한다.
function read() {
  try {
    const stored = JSON.parse(localStorage.getItem(STORE_KEY))
    if (stored) return stored
  } catch {
    /* 저장소를 못 읽으면 시드로 시작한다 */
  }
  return seed()
}

function save(db) {
  try {
    localStorage.setItem(STORE_KEY, JSON.stringify(db))
  } catch {
    /* 저장하지 못해도 이 화면에서는 계속 동작한다 */
  }
}

export function resetMockLinks() {
  try {
    localStorage.removeItem(STORE_KEY)
  } catch {
    /* 지울 것이 없다 */
  }
}

const mock = {
  list: async (workspaceId, page, size) => {
    mockAssertMember(workspaceId)
    const all = read()
      .links.filter((l) => l.workspaceId === workspaceId)
      .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
    return {
      links: all.slice(page * size, (page + 1) * size),
      pagination: {
        page,
        size,
        totalElements: all.length,
        totalPages: Math.ceil(all.length / size)
      }
    }
  },
  create: async (workspaceId, { originalUrl, title, expiresAt }) => {
    mockAssertMember(workspaceId)
    const db = read()
    if (db.links.filter((l) => l.workspaceId === workspaceId).length >= MOCK_TEAM_LINK_LIMIT) {
      throw new ApiError(
        409,
        `이 팀의 링크가 ${MOCK_TEAM_LINK_LIMIT}개로 가득 찼어요. 안 쓰는 링크를 지운 뒤 다시 만들어 주세요.`
      )
    }
    const code = Math.random().toString(36).slice(2, 8)
    const link = {
      linkId: `${MOCK_ID}${Date.now()}`,
      workspaceId,
      shortUrl: `https://snipy.test/${code}`,
      originalUrl,
      title: title ?? null,
      creator: mockMe().name,
      createdAt: new Date().toISOString(),
      expiresAt
    }
    db.links.push(link)
    save(db)
    return link
  },
  update: async (linkId, patch) => {
    const db = read()
    const link = mockFind(db, linkId)
    for (const [k, v] of Object.entries(patch)) if (v !== undefined) link[k] = v
    save(db)
    return link
  },
  remove: async (linkId) => {
    const db = read()
    const link = mockFind(db, linkId)
    db.links = db.links.filter((l) => l !== link)
    save(db)
  }
}

function mockFind(db, linkId) {
  const link = db.links.find((l) => l.linkId === linkId)
  if (!link) throw new ApiError(404, '링크를 찾을 수 없어요.')
  try {
    mockAssertMember(link.workspaceId)
  } catch {
    throw new ApiError(403, '다른 팀의 링크라서 볼 수 없어요.')
  }
  return link
}
