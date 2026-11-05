// 알림 API(#209) 경로와, 알림 종류별 문구를 본다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { alertMessage, getAlerts } from './alerts'

const calls = []

beforeEach(() => {
  calls.length = 0
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url) => {
      // CI는 prod 주소(VITE_API_BASE_URL)로 빌드하므로 base는 떼고 경로만 비교한다
      calls.push(url.replace(/^.*?(?=\/workspaces\/)/, ''))
      const alerts = [{ type: 'EXPIRING_SOON', level: 'ACTION', link: {}, detail: { daysLeft: 2 } }]
      return new Response(JSON.stringify({ data: { alerts } }), { status: 200 })
    })
  )
})

afterEach(() => vi.unstubAllGlobals())

describe('팀 알림', () => {
  it('워크스페이스 경로를 부르고 alerts 배열을 돌려준다', async () => {
    const alerts = await getAlerts({ workspaceId: 'ws-marketing' })
    expect(calls).toEqual(['/workspaces/ws-marketing/alerts'])
    expect(alerts).toHaveLength(1)
  })

  it('만료 임박은 남은 일수로, 0일이면 오늘 만료로 적는다', () => {
    expect(alertMessage({ type: 'EXPIRING_SOON', detail: { daysLeft: 3 } })).toBe('3일 뒤 만료돼요')
    expect(alertMessage({ type: 'EXPIRING_SOON', detail: { daysLeft: 0 } })).toBe('오늘 만료돼요')
  })

  it('방문자 급증은 오늘 클릭과 평균 대비 배수를 적는다', () => {
    const detail = { todayClicks: 1200, avgDailyClicks: 300, ratio: 4 }
    expect(alertMessage({ type: 'TRAFFIC_SPIKE', detail })).toBe(
      '오늘 클릭 1,200회, 최근 7일 평균의 4배'
    )
  })

  it('모르는 종류는 빈 문자열이다', () => {
    expect(alertMessage({ type: 'NEW_KIND', detail: {} })).toBe('')
  })
})
