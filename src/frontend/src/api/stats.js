// 워크스페이스 통계 API(/api/workspaces/{workspaceId}/stats/*, #208·#237). 권한은 그 팀의 멤버다.
// 응답은 대상과 상관없이 data.groups 이고, 링크 하나를 요청하면 groups[0]이 그 링크다.
// linkId는 링크의 외부 노출용 UUID(link_id 컬럼)다. ws는 선택한 워크스페이스({ workspaceId })다.
import { apiFetch } from './http'

async function fetchGroups(ws, name, params) {
  const query = new URLSearchParams(params)
  const { data } = await apiFetch(`/workspaces/${ws.workspaceId}/stats/${name}?${query}`)
  return data.groups
}

// { linkId, daily: [{ date, clickCount, visitorCount }], summary }
export async function getDailyStats(ws, linkId, from, to) {
  const groups = await fetchGroups(ws, 'daily', { linkIds: linkId, from, to })
  return groups[0]
}

// 링크마다 묶음 하나가 요청한 순서대로 온다. 슬러그·제목은 응답에 없으니 화면이 링크 목록에서 붙인다.
export async function compareLinks(ws, linkIds, from, to) {
  const groups = await fetchGroups(ws, 'daily', { linkIds: linkIds.join(','), from, to })
  return groups.map((g) => ({
    linkId: g.linkId,
    totalClicks: g.summary.totalClicks,
    sumOfDailyVisitors: g.summary.sumOfDailyVisitors,
    peakDailyClicks: g.summary.peakClicks
  }))
}

// type: REFERRER · DEVICE · COUNTRY · REGION · OS · BROWSER · HOUR. { linkId, breakdown: [{ value, clickCount, percentage }] }
// 분포는 배치 집계만 쓰므로 오늘 클릭은 들어 있지 않다.
export async function getBreakdown(ws, linkId, type, from, to) {
  const groups = await fetchGroups(ws, 'breakdown', { linkIds: linkId, type, from, to })
  return groups[0]
}

export async function getReferrerStats(ws, linkId, from, to) {
  return (await getBreakdown(ws, linkId, 'REFERRER', from, to)).breakdown
}

// { linkId, clickCount, uniqueVisitorCount } 오늘(KST) 값
export async function getRealtimeCount(ws, linkId) {
  const groups = await fetchGroups(ws, 'realtime', { linkIds: linkId })
  return groups[0]
}

// 서버의 MetricChange.of와 같은 규칙이다. 그제가 0이면 어제도 0일 때만 0%, 아니면 100%로 적고 소수 첫째 자리까지 쓴다.
export function changeOf(base, previous) {
  const rate = previous === 0 ? (base === 0 ? 0 : 100) : ((base - previous) / previous) * 100
  return { base, previous, changeRate: Math.round(rate * 10) / 10 }
}

// KST 기준 오늘에서 n일 전 날짜. 서버가 KST로 집계하므로 브라우저 시간대와 상관없이 KST로 센다.
export const kstDay = (n) =>
  new Date(Date.now() + 9 * 3_600_000 - n * 86_400_000).toISOString().slice(0, 10)

// 새 API에는 전일 대비가 없어서 이미 받은 일별 값에서 어제와 그제를 골라 계산한다. 오늘은 배치 전이라 뺀다.
// daily는 어제·그제를 포함하는 기간(링크 통계 화면은 7일 이상)이어야 한다. 없는 날은 0으로 본다.
export function dailyChangeOf(daily) {
  const [base, previous] = [kstDay(1), kstDay(2)]
  const at = (date, key) => daily.find((d) => d.date === date)?.[key] ?? 0
  return {
    clicks: changeOf(at(base, 'clickCount'), at(previous, 'clickCount')),
    visitors: changeOf(at(base, 'visitorCount'), at(previous, 'visitorCount'))
  }
}
