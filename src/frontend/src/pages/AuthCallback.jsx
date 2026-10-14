import { useEffect, useRef } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { toast } from '../components/Toast'
import { getProvider, consumeState } from '../auth/providers'
import { POST_LOGIN_REDIRECT_KEY } from './Login'

// 인가 서버가 /auth/:provider/callback 으로 돌려보낼 때 붙는 ?code=...&state=... 를 처리한다.
export function AuthCallback() {
  const { provider: providerId } = useParams()
  const [params] = useSearchParams()
  const { loginWithSocial } = useAuth()
  const navigate = useNavigate()
  const ran = useRef(false) // StrictMode 이중 호출 시 같은 code로 두 번 교환 요청을 보내는 것 방지

  useEffect(() => {
    if (ran.current) return
    ran.current = true

    const fail = (message) => {
      toast(message, 'error')
      navigate('/login', { replace: true })
    }

    const provider = getProvider(providerId)
    if (!provider) return fail('지원하지 않는 로그인 방식입니다.')

    // state는 성공·실패와 상관없이 한 번 꺼내서 지운다.
    const stateOk = consumeState(provider.id, params.get('state'))
    if (params.get('error')) return fail('로그인이 취소되었습니다.')
    if (!stateOk) return fail('로그인 요청이 올바르지 않습니다. 다시 시도해주세요.')

    const code = params.get('code')
    if (!code) return fail('로그인 코드가 없습니다.')

    loginWithSocial(provider.id, code, params.get('state'))
      .then(({ signupRequired }) => {
        if (signupRequired) {
          // 목적지는 가입을 마친 뒤 쓰므로 지우지 않는다.
          navigate('/signup/profile', { replace: true })
          return
        }
        const redirectTo = sessionStorage.getItem(POST_LOGIN_REDIRECT_KEY) || '/dashboard'
        sessionStorage.removeItem(POST_LOGIN_REDIRECT_KEY)
        toast('로그인되었습니다.', 'success')
        navigate(redirectTo, { replace: true })
      })
      // 403(탈퇴 계정)의 서버 메시지에는 사용자 ID가 들어 있어서 그대로 보여주지 않는다.
      .catch((err) => fail(err.status === 403 ? '탈퇴한 계정입니다.' : err.message || '로그인에 실패했습니다.'))
  }, [providerId, params, loginWithSocial, navigate])

  return <div className="page">로그인 처리 중...</div>
}
