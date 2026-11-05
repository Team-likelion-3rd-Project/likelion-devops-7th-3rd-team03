// 실제 management-service(Spring Boot) 백엔드로 나가는 유일한 통로.
// 개발 중엔 vite.config.js의 server.proxy가 /api -> 백엔드로 넘겨주므로 CORS 설정 없이 붙는다.
const API_BASE = import.meta.env.VITE_API_BASE_URL || '/api'

const ACCESS_TOKEN_KEY = 'linkshortener_access_token'
const REFRESH_TOKEN_KEY = 'linkshortener_refresh_token'

// 토큰은 localStorage(모든 탭이 공유)에 둔다. 개발용 테스트 계정(auth/devLogin.js)만 sessionStorage(탭마다 따로)에
// 둬서, 탭 하나는 관리자로 다른 탭은 멤버로 로그인해 두 계정을 나란히 시험할 수 있다. sessionStorage에 있으면 그쪽이 우선이다.
export function getAccessToken() {
  return sessionStorage.getItem(ACCESS_TOKEN_KEY) ?? localStorage.getItem(ACCESS_TOKEN_KEY)
}

export function getRefreshToken() {
  return sessionStorage.getItem(REFRESH_TOKEN_KEY) ?? localStorage.getItem(REFRESH_TOKEN_KEY)
}

export function setTokens({ accessToken, refreshToken }, { perTab = false } = {}) {
  const storage = perTab ? sessionStorage : localStorage
  storage.setItem(ACCESS_TOKEN_KEY, accessToken)
  storage.setItem(REFRESH_TOKEN_KEY, refreshToken)
}

export function clearTokens() {
  // 이 탭이 테스트 계정이면 그 탭의 로그인만 끊고, 다른 탭의 실제 로그인은 건드리지 않는다
  const storage = sessionStorage.getItem(ACCESS_TOKEN_KEY) ? sessionStorage : localStorage
  storage.removeItem(ACCESS_TOKEN_KEY)
  storage.removeItem(REFRESH_TOKEN_KEY)
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
    body: body !== undefined ? JSON.stringify(body) : undefined
  })

  if (res.status === 401) {
    // 개발용 테스트 계정(auth/devLogin.js)의 토큰은 서명이 없어 백엔드가 항상 거절한다.
    // 토큰을 지우면 로그아웃되고 팀 mock 시험이 끊기므로, 오류만 돌려준다.
    if (import.meta.env.DEV && getRefreshToken() === 'dev') {
      throw new ApiError(
        401,
        '테스트 계정은 백엔드 API를 쓸 수 없어요. 실제 로그인으로 확인해 주세요.'
      )
    }
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
