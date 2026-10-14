// 실제 management-service(Spring Boot) 백엔드로 나가는 유일한 통로.
// 개발 중엔 vite.config.js의 server.proxy가 /api -> 백엔드로 넘겨주므로 CORS 설정 없이 붙는다.
const API_BASE = import.meta.env.VITE_API_BASE_URL || '/api'

const ACCESS_TOKEN_KEY = 'linkshortener_access_token'
const REFRESH_TOKEN_KEY = 'linkshortener_refresh_token'

export function getAccessToken() {
  return localStorage.getItem(ACCESS_TOKEN_KEY)
}

export function getRefreshToken() {
  return localStorage.getItem(REFRESH_TOKEN_KEY)
}

export function setTokens({ accessToken, refreshToken }) {
  localStorage.setItem(ACCESS_TOKEN_KEY, accessToken)
  localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken)
}

export function clearTokens() {
  localStorage.removeItem(ACCESS_TOKEN_KEY)
  localStorage.removeItem(REFRESH_TOKEN_KEY)
}

export class ApiError extends Error {
  constructor(status, message) {
    super(message)
    this.status = status
  }
}

// 백엔드 오류 본문은 두 가지다. auth 오류는 {"message": "..."}, 입력 검증 오류는 {"code":400,"error":{"message":"..."}}.
// 가능하면 message만 꺼내 사용자에게 보여준다.
function errorMessage(text) {
  try {
    const body = JSON.parse(text)
    return body.message || body.error?.message || text
  } catch {
    return text
  }
}

export async function apiFetch(path, { method = 'GET', body, auth = true } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  if (auth) {
    const token = getAccessToken()
    if (token) headers.Authorization = `Bearer ${token}`
  }

  const res = await fetch(`${API_BASE}${path}`, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })

  if (res.status === 401) {
    clearTokens()
    throw new ApiError(401, '인증이 만료되었습니다. 다시 로그인해주세요.')
  }
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new ApiError(res.status, errorMessage(text) || `요청에 실패했습니다 (${res.status})`)
  }
  if (res.status === 204) return null
  return res.json()
}
