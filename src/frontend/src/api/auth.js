// AuthController(/api/auth/*) 연동. 카카오 인가코드 교환/로그아웃/토큰 재발급.
import { apiFetch, setTokens, clearTokens, getRefreshToken } from './http'

export async function kakaoLogin(code) {
  const data = await apiFetch('/auth/kakao/login', { method: 'POST', body: { code }, auth: false })
  setTokens(data)
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
