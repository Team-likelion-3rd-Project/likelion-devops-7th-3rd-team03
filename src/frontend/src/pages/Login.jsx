import { useLocation } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'

const KAKAO_CLIENT_ID = import.meta.env.VITE_KAKAO_CLIENT_ID
const KAKAO_REDIRECT_URI = import.meta.env.VITE_KAKAO_REDIRECT_URI
export const POST_LOGIN_REDIRECT_KEY = 'linkshortener_post_login_redirect'

export function Login() {
  const { loading } = useAuth()
  const location = useLocation()

  function handleLogin() {
    // 카카오 인증 페이지로 벗어났다 돌아오므로 라우터 state가 아니라 sessionStorage로 목적지를 넘긴다.
    sessionStorage.setItem(POST_LOGIN_REDIRECT_KEY, location.state?.from || '/dashboard')

    const url = new URL('https://kauth.kakao.com/oauth/authorize')
    url.searchParams.set('client_id', KAKAO_CLIENT_ID || '')
    url.searchParams.set('redirect_uri', KAKAO_REDIRECT_URI || '')
    url.searchParams.set('response_type', 'code')
    window.location.href = url.toString()
  }

  return (
    <div className="page page--narrow">
      <div className="card login-card">
        <h1>로그인</h1>
        <p className="hero__sub">카카오 계정으로 로그인합니다.</p>

        {!KAKAO_CLIENT_ID && (
          <p className="hero__sub" style={{ color: 'var(--danger, #e5484d)' }}>
            VITE_KAKAO_CLIENT_ID / VITE_KAKAO_REDIRECT_URI가 설정되지 않았습니다.
            프론트엔드 <code>.env</code>를 확인해주세요.
          </p>
        )}

        <button className="btn btn--primary btn--block" onClick={handleLogin} disabled={loading}>
          {loading ? '로그인 중...' : '카카오로 로그인'}
        </button>
      </div>
    </div>
  )
}
