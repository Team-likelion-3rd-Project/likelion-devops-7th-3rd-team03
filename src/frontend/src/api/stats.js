// 워크스페이스 통계 API(/api/workspaces/{workspaceId}/stats/*, #208·#237). 권한은 그 팀의 멤버다.
// 응답은 대상과 상관없이 data.groups 이고, 링크 하나를 요청하면 groups[0]이 그 링크다.
// linkId는 링크의 외부 노출용 UUID(link_id 컬럼)다. ws는 선택한 워크스페이스({ workspaceId })다.
import { ApiError, apiFetch } from './http'

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

// ---- 홈 화면(#210)용: linkIds를 빼면 팀 전체를 한 묶음으로 합쳐 준다 ----

export async function getTeamDaily(ws, from, to) {
  const groups = await fetchGroups(ws, 'daily', { from, to })
  return groups[0]
}

// 분포 행 [{ value, clickCount, percentage }]. 배치 집계만 쓰므로 오늘은 들어 있지 않다.
export async function getTeamBreakdown(ws, type, from, to) {
  const groups = await fetchGroups(ws, 'breakdown', { type, from, to })
  return groups[0].breakdown
}

const MAX_LINK_TARGETS = 20 // 서버가 한 번에 받는 linkIds 수

// 링크마다 기간 클릭을 구한다. 서버 제한(20개) 때문에 나눠 부른다.
export async function getLinkTotals(ws, linkIds, from, to) {
  const chunks = []
  for (let i = 0; i < linkIds.length; i += MAX_LINK_TARGETS) {
    chunks.push(linkIds.slice(i, i + MAX_LINK_TARGETS))
  }
  const rows = await Promise.all(chunks.map((ids) => compareLinks(ws, ids, from, to)))
  return rows.flat()
}

// 클릭이 있는 링크만 클릭 많은 순으로 n개. totals = getLinkTotals 결과, links = 링크 목록
export function topLinks(links, totals, n = 5) {
  const byId = new Map(links.map((l) => [l.linkId, l]))
  return totals
    .filter((t) => t.totalClicks > 0 && byId.has(t.linkId))
    .sort((a, b) => b.totalClicks - a.totalClicks)
    .slice(0, n)
    .map((t) => ({ ...byId.get(t.linkId), totalClicks: t.totalClicks }))
}

// 백엔드 Link.isExpired()와 같은 경계값(now >= expiresAt이면 만료)
export const isActiveLink = (link) =>
  !link.expiresAt || Date.now() < new Date(link.expiresAt).getTime()

// 홈의 기간 선택(1·7·14·30일)을 날짜로 바꾼다. 지표·그래프는 오늘을 포함하고(from~to),
// 분포는 배치 집계라 어제까지다(distFrom~distTo). 모두 KST 날짜다.
export function periodRange(days) {
  return { from: kstDay(days - 1), to: kstDay(0), distFrom: kstDay(days), distTo: kstDay(1) }
}

// 인기 링크: 링크 목록 전체의 기간 클릭을 합쳐 상위 n개를 고른다. 그 사이 링크가 삭제됐으면 서버가 404를 주므로,
// 목록을 다시 받아(refetch) 한 번만 다시 시도한다. 404가 아닌 오류와 두 번째 실패는 그대로 던진다.
export async function loadTopLinks(ws, links, from, to, refetch, n = 5) {
  try {
    return topLinks(
      links,
      await getLinkTotals(
        ws,
        links.map((l) => l.linkId),
        from,
        to
      ),
      n
    )
  } catch (err) {
    if (!(err instanceof ApiError) || err.status !== 404) throw err
    const fresh = await refetch()
    return topLinks(
      fresh,
      await getLinkTotals(
        ws,
        fresh.map((l) => l.linkId),
        from,
        to
      ),
      n
    )
  }
}
