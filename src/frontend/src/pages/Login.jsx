import { useLocation } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { enabledProviders, isConfigured, buildAuthorizeUrl, createState } from '../auth/providers'

export const POST_LOGIN_REDIRECT_KEY = 'linkshortener_post_login_redirect'

export function Login() {
  const { loading } = useAuth()
  const location = useLocation()
  const providers = enabledProviders()

  function handleLogin(provider) {
    // 인증 페이지로 벗어났다 돌아오므로 라우터 state가 아니라 sessionStorage로 목적지를 넘긴다.
    sessionStorage.setItem(POST_LOGIN_REDIRECT_KEY, location.state?.from || '/dashboard')
    window.location.href = buildAuthorizeUrl(provider, createState(provider.id))
  }

  return (
    <div className="page page--narrow">
      <div className="card login-card">
        <h1>로그인</h1>
        <p className="hero__sub">소셜 계정으로 로그인합니다.</p>

        <div className="login-providers">
          {providers.map((p) => (
            <div key={p.id}>
              <button
                className="btn btn--primary btn--block"
                onClick={() => handleLogin(p)}
                disabled={loading || !isConfigured(p)}
              >
                {loading ? '로그인 중...' : `${p.label}로 로그인`}
              </button>
              {!isConfigured(p) && (
                <p className="form-error">
                  VITE_{p.id.toUpperCase()}_CLIENT_ID / VITE_{p.id.toUpperCase()}_REDIRECT_URI가
                  설정되지 않았습니다. 프론트엔드 <code>.env</code>를 확인해주세요.
                </p>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
