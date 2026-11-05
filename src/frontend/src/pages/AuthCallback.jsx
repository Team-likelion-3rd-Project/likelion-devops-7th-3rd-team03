import { useEffect } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import Box from '@cloudscape-design/components/box'
import StatusIndicator from '@cloudscape-design/components/status-indicator'
import { useAuth } from '../context/AuthContext'
import { toast } from '../components/Toast'
import { getProvider, consumeState } from '../auth/providers'
import { POST_LOGIN_REDIRECT_KEY } from './Login'

// 이미 처리한 콜백 주소(?code=...&state=...). 컴포넌트 밖에 둔다: 로그인에 성공하면 상단바가 바뀌면서(GuestShell → AppShell)
// 이 화면이 새로 만들어지는데, 컴포넌트 안의 표시는 그때 사라져 같은 code를 다시 처리하다 /login으로 보내 버린다.
// StrictMode 이중 호출 때 같은 code로 교환 요청을 두 번 보내는 것도 같은 표시로 막는다.
const handled = new Set()

// 인가 서버가 /auth/:provider/callback 으로 돌려보낼 때 붙는 ?code=...&state=... 를 처리한다.
export function AuthCallback() {
  const { provider: providerId } = useParams()
  const [params] = useSearchParams()
  const { loginWithSocial } = useAuth()
  const navigate = useNavigate()

  useEffect(() => {
    const key = params.toString()
    if (handled.has(key)) return
    handled.add(key)

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
      .catch((err) =>
        fail(err.status === 403 ? '탈퇴한 계정입니다.' : err.message || '로그인에 실패했습니다.')
      )
  }, [providerId, params, loginWithSocial, navigate])

  return (
    <Box padding="xxl" textAlign="center">
      <StatusIndicator type="loading">로그인 처리 중...</StatusIndicator>
    </Box>
  )
}
