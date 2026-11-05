// 통계 API는 워크스페이스 경로(#237)를 부른다. 경로·파라미터와, 새 API에 없는 전일 대비 계산을 본다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  changeOf,
  compareLinks,
  getBreakdown,
  dailyChangeOf,
  getDailyStats,
  getRealtimeCount,
  getReferrerStats
} from './stats'

const ws = { workspaceId: 'ws-marketing' }
const calls = []
let groups

beforeEach(() => {
  calls.length = 0
  groups = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url) => {
      // CI는 prod 주소(VITE_API_BASE_URL)로 빌드하므로 base는 떼고 경로만 비교한다
      calls.push(decodeURIComponent(url).replace(/^.*?(?=\/workspaces\/)/, ''))
      return new Response(JSON.stringify({ data: { groups } }), { status: 200 })
    })
  )
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('워크스페이스 통계 호출', () => {
  it('일별 통계는 팀 경로에 linkIds로 부르고 첫 묶음을 돌려준다', async () => {
    groups = [{ linkId: 'L1', daily: [], summary: { totalClicks: 3 } }]
    const res = await getDailyStats(ws, 'L1', '2026-11-01', '2026-11-05')
    expect(calls).toEqual([
      '/workspaces/ws-marketing/stats/daily?linkIds=L1&from=2026-11-01&to=2026-11-05'
    ])
    expect(res).toMatchObject({ linkId: 'L1', summary: { totalClicks: 3 } })
  })

  it('링크 비교는 linkIds를 한 번에 보내고 링크별 요약을 요청 순서대로 준다', async () => {
    groups = [
      { linkId: 'A', summary: { totalClicks: 10, sumOfDailyVisitors: 7, peakClicks: 4 } },
      { linkId: 'B', summary: { totalClicks: 2, sumOfDailyVisitors: 2, peakClicks: 1 } }
    ]
    const rows = await compareLinks(ws, ['A', 'B'], '2026-11-01', '2026-11-05')
    expect(calls[0]).toContain('linkIds=A,B')
    expect(rows).toEqual([
      { linkId: 'A', totalClicks: 10, sumOfDailyVisitors: 7, peakDailyClicks: 4 },
      { linkId: 'B', totalClicks: 2, sumOfDailyVisitors: 2, peakDailyClicks: 1 }
    ])
  })

  it('유입 경로는 breakdown type=REFERRER이고 분포 행만 돌려준다', async () => {
    groups = [{ linkId: 'L1', breakdown: [{ value: '직접', clickCount: 5, percentage: 100 }] }]
    const rows = await getReferrerStats(ws, 'L1', '2026-11-01', '2026-11-05')
    expect(calls[0]).toContain('/stats/breakdown?linkIds=L1&type=REFERRER')
    expect(rows).toEqual([{ value: '직접', clickCount: 5, percentage: 100 }])
  })

  it('분포 type을 그대로 넘긴다 (국가)', async () => {
    groups = [{ linkId: 'L1', breakdown: [] }]
    await getBreakdown(ws, 'L1', 'COUNTRY', '2026-11-01', '2026-11-05')
    expect(calls[0]).toContain('type=COUNTRY')
  })

  it('실시간은 첫 묶음의 clickCount·uniqueVisitorCount를 준다', async () => {
    groups = [{ linkId: 'L1', clickCount: 4, uniqueVisitorCount: 3 }]
    expect(await getRealtimeCount(ws, 'L1')).toMatchObject({ clickCount: 4, uniqueVisitorCount: 3 })
    expect(calls[0]).toBe('/workspaces/ws-marketing/stats/realtime?linkIds=L1')
  })
})

describe('전일 대비 계산 (서버 MetricChange.of와 같은 규칙)', () => {
  it('증감률은 소수 첫째 자리까지', () => {
    expect(changeOf(145, 120)).toEqual({ base: 145, previous: 120, changeRate: 20.8 })
    expect(changeOf(110, 98).changeRate).toBe(12.2)
    expect(changeOf(80, 100).changeRate).toBe(-20)
  })

  it('그제가 0이면 어제도 0일 때만 0%, 아니면 100%', () => {
    expect(changeOf(0, 0).changeRate).toBe(0)
    expect(changeOf(5, 0).changeRate).toBe(100)
  })
})

describe('dailyChangeOf', () => {
  it('KST 기준 어제와 그제 값으로 계산하고, 값이 없는 날은 0으로 본다', () => {
    // UTC로는 11-04 16:00이지만 KST로는 11-05 01:00이다. 어제는 11-04, 그제는 11-03이어야 한다
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-11-04T16:00:00Z'))
    const daily = [
      { date: '2026-11-02', clickCount: 999, visitorCount: 999 },
      { date: '2026-11-04', clickCount: 145, visitorCount: 110 },
      { date: '2026-11-05', clickCount: 7, visitorCount: 5 }
    ]
    const res = dailyChangeOf(daily)
    expect(res.clicks).toEqual({ base: 145, previous: 0, changeRate: 100 })
    expect(res.visitors).toEqual({ base: 110, previous: 0, changeRate: 100 })
  })

  it('어제·그제가 둘 다 있으면 증감률을 소수 첫째 자리까지 계산한다', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-11-04T16:00:00Z'))
    const daily = [
      { date: '2026-11-03', clickCount: 200, visitorCount: 100 },
      { date: '2026-11-04', clickCount: 150, visitorCount: 100 }
    ]
    expect(dailyChangeOf(daily).clicks.changeRate).toBe(-25)
    expect(dailyChangeOf(daily).visitors.changeRate).toBe(0)
  })
})
