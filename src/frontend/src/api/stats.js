// StatsController(/api/links/*) 연동. linkId는 링크의 외부 노출용 UUID(link_id 컬럼)다.
import { apiFetch } from './http'

export function getDailyStats(linkId, from, to) {
  const params = new URLSearchParams({ from, to })
  return apiFetch(`/links/${linkId}/stats/daily?${params}`)
}

export function compareLinks(linkIds, from, to) {
  const params = new URLSearchParams({ from, to })
  linkIds.forEach((id) => params.append('linkIds', id))
  return apiFetch(`/links/stats/compare?${params}`)
}

export function getReferrerStats(linkId, from, to) {
  const params = new URLSearchParams({ from, to })
  return apiFetch(`/links/${linkId}/stats/referrers?${params}`)
}

export function getDailyChange(linkId, baseDate) {
  const params = baseDate ? `?${new URLSearchParams({ baseDate })}` : ''
  return apiFetch(`/links/${linkId}/stats/daily-change${params}`)
}

export function getBreakdown(linkId, type, from, to) {
  const params = new URLSearchParams({ type, from, to })
  return apiFetch(`/links/${linkId}/stats/breakdown?${params}`)
}

export function getRealtimeCount(linkId) {
  return apiFetch(`/links/${linkId}/stats/realtime`)
}
