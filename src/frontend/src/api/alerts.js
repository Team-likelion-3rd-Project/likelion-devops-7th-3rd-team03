// 팀 알림 API(#209). 알림은 저장하지 않고 조회할 때 서버가 계산한다. 팀 멤버만 볼 수 있다.
import { apiFetch } from './http'

// [{ type, level: 'ACTION' | 'WATCH', link: { linkId, title, shortUrl }, detail }] 급한 것부터 온다.
export async function getAlerts(ws) {
  const { data } = await apiFetch(`/workspaces/${ws.workspaceId}/alerts`)
  return data.alerts
}

// 화면에 보일 한 줄 설명. 모르는 종류가 와도 화면이 깨지지 않게 빈 문자열을 돌려준다.
export function alertMessage({ type, detail }) {
  if (type === 'EXPIRING_SOON') {
    return detail.daysLeft === 0 ? '오늘 만료돼요' : `${detail.daysLeft}일 뒤 만료돼요`
  }
  if (type === 'TRAFFIC_SPIKE') {
    return `오늘 클릭 ${detail.todayClicks.toLocaleString()}회, 최근 7일 평균의 ${detail.ratio}배`
  }
  return ''
}
