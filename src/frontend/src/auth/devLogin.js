// 개발 서버(npm run dev)에서 백엔드 없이 팀 기능을 시험하는 테스트 계정.
// 서명 없는 토큰이라 백엔드는 받아 주지 않는다. Login.jsx가 import.meta.env.DEV일 때만 버튼을 보여 준다.
import { setTokens } from '../api/http'

export const DEV_ACCOUNTS = [
  { userId: 'dev-admin', name: '테스트 관리자', email: 'admin@snipy.test' },
  { userId: 'dev-member', name: '테스트 멤버', email: 'member@snipy.test' }
]

const encode = (obj) => btoa(JSON.stringify(obj)).replace(/=+$/, '')

// 탭마다 따로 로그인한다(sessionStorage). 팀 mock 데이터만 탭끼리 공유한다.
export function devLogin(account) {
  setTokens(
    {
      accessToken: `${encode({ alg: 'none' })}.${encode({ sub: account.userId })}.dev`,
      refreshToken: 'dev'
    },
    { perTab: true }
  )
}

// 상단바에 보여 줄 이름. 테스트 계정이 아니면 사용자 ID 앞부분을 쓴다.
export function displayName(userId) {
  return DEV_ACCOUNTS.find((a) => a.userId === userId)?.name ?? userId.slice(0, 8)
}
