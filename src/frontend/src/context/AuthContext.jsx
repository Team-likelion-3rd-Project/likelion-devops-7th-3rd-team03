import { createContext, useContext, useEffect, useState, useCallback } from 'react'
import { getAccessToken, getRefreshToken, clearTokens } from '../api/http'
import * as authApi from '../api/auth'
import { getStoredProvider, setStoredProvider, clearStoredProvider } from '../auth/providers'

const AuthContext = createContext(null)
const KAKAO_CLIENT_ID = import.meta.env.VITE_KAKAO_CLIENT_ID

// 백엔드는 로그인 응답에 프로필을 담아주지 않는다 (LoginResponse = signupRequired + 토큰 또는 가입 대기 정보).
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
  const [pendingSignup, setPendingSignup] = useState(() => authApi.loadPendingSignup())

  // 반환: { signupRequired: true } 이면 추가정보 입력 페이지로, false 이면 로그인 완료.
  const loginWithSocial = useCallback(async (provider, code, state) => {
    setLoading(true)
    try {
      const data = await authApi.socialLogin(provider, code, state)
      if (data.signupRequired) {
        setPendingSignup(authApi.loadPendingSignup())
        return { signupRequired: true }
      }
      setStoredProvider(provider)
      setUser(userFromStoredToken())
      return { signupRequired: false }
    } finally {
      setLoading(false)
    }
  }, [])

  const signup = useCallback(async ({ nickname, email, profileImageUrl }) => {
    const pending = authApi.loadPendingSignup()
    if (!pending) throw new Error('가입 정보가 없습니다. 다시 로그인해주세요.')
    await authApi.completeSignup({
      signupToken: pending.signupToken,
      nickname,
      email,
      profileImageUrl
    })
    setStoredProvider(pending.provider)
    setPendingSignup(null)
    setUser(userFromStoredToken())
  }, [])

  const cancelSignup = useCallback(() => {
    authApi.clearPendingSignup()
    setPendingSignup(null)
  }, [])

  const logout = useCallback(async () => {
    const provider = getStoredProvider()
    await authApi.logout()
    authApi.clearPendingSignup()
    clearStoredProvider()
    setPendingSignup(null)
    setUser(null)

    // 우리 서비스 토큰만 폐기하고 끝내면 카카오 계정 세션은 그대로 남아있어서,
    // 다음에 "카카오로 로그인"을 눌러도 자동으로 다시 로그인돼버린다.
    // 카카오로 로그인한 사용자만 카카오 로그아웃 페이지로 보내 카카오 쪽 세션까지 끊고 우리 앱 홈으로 돌아오게 한다.
    // 구글·네이버 사용자를 카카오 로그아웃으로 보내면 안 된다.
    if (provider === 'kakao' && KAKAO_CLIENT_ID) {
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
    <AuthContext.Provider
      value={{ user, loading, pendingSignup, loginWithSocial, signup, cancelSignup, logout }}
    >
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
