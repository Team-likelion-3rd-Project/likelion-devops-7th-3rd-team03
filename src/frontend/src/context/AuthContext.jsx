import { createContext, useContext, useEffect, useState, useCallback } from 'react'
import { getAccessToken, getRefreshToken, clearTokens } from '../api/http'
import * as authApi from '../api/auth'

const AuthContext = createContext(null)
const KAKAO_CLIENT_ID = import.meta.env.VITE_KAKAO_CLIENT_ID

// 백엔드는 로그인 응답에 프로필을 담아주지 않는다 (LoginResponse = accessToken/refreshToken/isNewUser 뿐).
// JWT subject가 곧 User.userId(UUID)이므로 화면 표시용으로만 토큰을 디코딩한다 (서명 검증 아님).
function decodeUserId(accessToken) {
  try {
    return JSON.parse(atob(accessToken.split('.')[1])).sub || null
  } catch {
    return null
  }
}

function userFromStoredToken() {
  const token = getAccessToken()
  if (!token) return null
  const userId = decodeUserId(token)
  return userId ? { userId } : null
}

export function AuthProvider({ children }) {
  const [user, setUser] = useState(() => userFromStoredToken())
  const [loading, setLoading] = useState(false)

  const loginWithKakaoCode = useCallback(async (code) => {
    setLoading(true)
    try {
      await authApi.kakaoLogin(code)
      const u = userFromStoredToken()
      setUser(u)
      return u
    } finally {
      setLoading(false)
    }
  }, [])

  const logout = useCallback(async () => {
    await authApi.logout()
    setUser(null)

    // 우리 서비스 토큰만 폐기하고 끝내면 카카오 계정 세션은 그대로 남아있어서,
    // 다음에 "카카오로 로그인"을 눌러도 자동으로 다시 로그인돼버린다.
    // 카카오 로그아웃 페이지로 보내 카카오 쪽 세션까지 끊고 우리 앱 홈으로 돌아오게 한다.
    if (KAKAO_CLIENT_ID) {
      const url = new URL('https://kauth.kakao.com/oauth/logout')
      url.searchParams.set('client_id', KAKAO_CLIENT_ID)
      url.searchParams.set('logout_redirect_uri', `${window.location.origin}/`)
      window.location.href = url.toString()
    }
  }, [])

  // access token이 없지만 refresh token은 남아있는 경우 (새로고침 등) 조용히 재발급 시도
  useEffect(() => {
    if (!getAccessToken() && getRefreshToken()) {
      authApi
        .reissue()
        .then(() => setUser(userFromStoredToken()))
        .catch(() => clearTokens())
    }
  }, [])

  return (
    <AuthContext.Provider value={{ user, loading, loginWithKakaoCode, logout }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
