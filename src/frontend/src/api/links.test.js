// 링크 API의 팀 기준 동작(#205). mock 규칙과, 개인 팀·실제 API 모드가 부르는 경로를 본다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MOCK_TEAM_LINK_LIMIT, createLink, deleteLink, listLinks, updateLink } from './links'
import { loginAs, resetMocks } from '../test/helpers'

const marketing = { workspaceId: 'ws-marketing', personal: false }
const events = { workspaceId: 'ws-events', personal: false }
const design = { workspaceId: 'ws-design', personal: false }
const body = {
  originalUrl: 'https://example.com/x',
  title: '새 링크',
  expiresAt: '2099-01-01T00:00:00Z'
}

beforeEach(() => {
  resetMocks()
  loginAs('dev-admin')
})

describe('팀 링크 목록 (mock)', () => {
  it('그 팀의 링크만, 만든 사람과 함께, 최신순으로 보여 준다', async () => {
    const { links, pagination } = await listLinks(marketing)
    expect(links.map((l) => l.creator)).toEqual(['박서연', '테스트 관리자', '이도윤'])
    expect(links.every((l) => l.workspaceId === 'ws-marketing')).toBe(true)
    expect(pagination).toMatchObject({ totalElements: 3, totalPages: 1 })
  })

  it('쪽 크기대로 자르고 전체 쪽 수를 계산한다', async () => {
    const { links, pagination } = await listLinks(marketing, 1, 2)
    expect(links).toHaveLength(1)
    expect(pagination).toMatchObject({ totalElements: 3, totalPages: 2 })
  })

  it('팀 멤버가 아니면 목록을 볼 수 없다', async () => {
    await expect(listLinks(design)).rejects.toMatchObject({ status: 403 })
  })
})

describe('팀 링크 생성 (mock)', () => {
  it('만든 사람이 내 이름으로 붙고, 같은 팀 멤버에게 보인다', async () => {
    const link = await createLink(marketing, body)
    expect(link).toMatchObject({ creator: '테스트 관리자', title: '새 링크' })
    loginAs('u2')
    expect((await listLinks(marketing)).links.map((l) => l.linkId)).toContain(link.linkId)
  })

  it('팀당 개수 제한에 걸리면 409와 안내 문구를 준다', async () => {
    const room = MOCK_TEAM_LINK_LIMIT - (await listLinks(marketing)).pagination.totalElements
    for (let i = 0; i < room; i++) await createLink(marketing, body)
    await expect(createLink(marketing, body)).rejects.toMatchObject({
      status: 409,
      message: expect.stringContaining(`${MOCK_TEAM_LINK_LIMIT}개`)
    })
  })

  it('개수 제한은 팀마다 따로 센다', async () => {
    const room = MOCK_TEAM_LINK_LIMIT - (await listLinks(marketing)).pagination.totalElements
    for (let i = 0; i < room; i++) await createLink(marketing, body)
    await expect(createLink(events, body)).resolves.toMatchObject({ workspaceId: 'ws-events' })
  })

  it('팀 멤버가 아니면 만들 수 없다', async () => {
    await expect(createLink(design, body)).rejects.toMatchObject({ status: 403 })
  })
})

describe('수정·삭제 (mock)', () => {
  it('같은 팀 멤버는 다른 사람이 만든 링크도 고치고 지운다', async () => {
    const [first] = (await listLinks(marketing)).links // 박서연이 만든 링크
    const edited = await updateLink(first.linkId, { title: '고친 제목' })
    expect(edited.title).toBe('고친 제목')
    await deleteLink(first.linkId)
    expect((await listLinks(marketing)).links.map((l) => l.linkId)).not.toContain(first.linkId)
  })

  it('수정은 값이 있는 필드만 바꾼다', async () => {
    const [first] = (await listLinks(marketing)).links
    const edited = await updateLink(first.linkId, { title: '제목만' })
    expect(edited.originalUrl).toBe(first.originalUrl)
  })

  it('다른 팀의 링크는 403이다', async () => {
    await expect(updateLink('mlink-seed-5', { title: 'x' })).rejects.toMatchObject({ status: 403 })
    await expect(deleteLink('mlink-seed-5')).rejects.toMatchObject({ status: 403 })
  })

  it('없는 링크는 404이다', async () => {
    await expect(deleteLink('mlink-nope')).rejects.toMatchObject({ status: 404 })
  })
})

describe('실제 API를 부르는 경우', () => {
  const calls = []

  beforeEach(() => {
    calls.length = 0
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url, init) => {
        calls.push({ url, method: init?.method ?? 'GET', body: init?.body })
        const data = url.includes('?')
          ? { links: [{ linkId: 'L1', creatorName: '김철수' }], pagination: { totalElements: 1 } }
          : { linkId: 'L2', createdBy: { name: '이영희' } }
        return new Response(JSON.stringify({ data }), { status: 200 })
      })
    )
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.unstubAllEnvs()
  })

  it('mock 모드의 개인 팀은 기존 /api/links를 쓰고 만든 사람 이름을 맞춰 준다', async () => {
    const personal = { workspaceId: 'ws-personal-dev-admin', personal: true }
    const { links } = await listLinks(personal, 0, 20)
    expect(calls[0].url).toBe('/api/links?page=0&size=20')
    expect(links[0].creator).toBe('김철수')
    const created = await createLink(personal, body)
    expect(calls[1]).toMatchObject({ url: '/api/links', method: 'POST' })
    expect(created.creator).toBe('이영희')
  })

  it('real 모드는 모든 워크스페이스가 /api/workspaces/{id}/links를 쓴다', async () => {
    vi.stubEnv('VITE_WORKSPACE_API', 'real')
    vi.resetModules()
    const real = await import('./links')
    await real.listLinks({ workspaceId: 'uuid-1', personal: true }, 2, 5)
    await real.createLink({ workspaceId: 'uuid-1', personal: false }, body)
    expect(calls[0].url).toBe('/api/workspaces/uuid-1/links?page=2&size=5')
    expect(calls[1]).toMatchObject({ url: '/api/workspaces/uuid-1/links', method: 'POST' })
  })

  it('수정·삭제는 모드와 관계없이 기존 /api/links/{id}를 쓴다', async () => {
    await updateLink('L9', { title: 't' })
    await deleteLink('L9')
    expect(calls[0]).toMatchObject({ url: '/api/links/L9', method: 'PATCH' })
    expect(calls[1]).toMatchObject({ url: '/api/links/L9', method: 'DELETE' })
  })
})
