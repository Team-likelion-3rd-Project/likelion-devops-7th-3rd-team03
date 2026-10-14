// AuthController(/api/auth/*) 연동. 소셜 인가코드 교환/가입 완료/로그아웃/토큰 재발급.
import { apiFetch, setTokens, clearTokens, getRefreshToken } from './http'

// 가입 대기 중인 소셜 사용자 정보. 새로고침해도 폼이 유지되도록 sessionStorage에 둔다 (백엔드 세션 TTL은 15분).
// 형태: { provider, signupToken, prefill: { nickname, email, profileImageUrl } }
const PENDING_SIGNUP_KEY = 'linkshortener_signup_pending'

export function loadPendingSignup() {
  try {
    return JSON.parse(sessionStorage.getItem(PENDING_SIGNUP_KEY))
  } catch {
    return null
  }
}

export function clearPendingSignup() {
  sessionStorage.removeItem(PENDING_SIGNUP_KEY)
}

// 로그인 응답은 두 가지다.
//  - 가입된 회원: { signupRequired:false, accessToken, refreshToken } -> 토큰 저장
//  - 미가입:      { signupRequired:true, signupToken, prefill } -> 토큰 없이 가입 대기 정보만 저장
export async function socialLogin(provider, code, state) {
  const data = await apiFetch(`/auth/${provider}/login`, { method: 'POST', body: { code, state }, auth: false })
  if (data.signupRequired) {
    sessionStorage.setItem(
      PENDING_SIGNUP_KEY,
      JSON.stringify({ provider, signupToken: data.signupToken, prefill: data.prefill || {} }),
    )
    return data
  }
  setTokens(data)
  return data
}

// 추가정보 입력 후 가입 완료. 백엔드 가입 API 명세가 확정되면 경로·필드는 이 함수에서만 바꾼다.
// 현재 가정: POST /auth/signup {signupToken, nickname, email, profileImageUrl} -> {accessToken, refreshToken}
export async function completeSignup({ signupToken, nickname, email, profileImageUrl }) {
  const data = await apiFetch('/auth/signup', {
    method: 'POST',
    body: { signupToken, nickname, email, profileImageUrl },
    auth: false,
  })
  setTokens(data)
  clearPendingSignup()
  return data
}

export async function logout() {
  const refreshToken = getRefreshToken()
  clearTokens()
  if (!refreshToken) return
  await apiFetch('/auth/logout', { method: 'POST', body: { refreshToken }, auth: false }).catch(() => {})
}

export async function reissue() {
  const refreshToken = getRefreshToken()
  if (!refreshToken) throw new Error('저장된 refresh token이 없습니다.')
  const data = await apiFetch('/auth/reissue', { method: 'POST', body: { refreshToken }, auth: false })
  setTokens(data)
  return data
}
