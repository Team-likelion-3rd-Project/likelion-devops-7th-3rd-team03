import { useEffect, useRef } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { toast } from '../components/Toast'
import { POST_LOGIN_REDIRECT_KEY } from './Login'

// 카카오 인가 서버가 VITE_KAKAO_REDIRECT_URI로 돌려보낼 때 붙는 ?code=...를 처리한다.
export function KakaoCallback() {
  const [params] = useSearchParams()
  const { loginWithKakaoCode } = useAuth()
  const navigate = useNavigate()
  const ran = useRef(false) // StrictMode 이중 호출 시 같은 code로 두 번 교환 요청을 보내는 것 방지

  useEffect(() => {
    if (ran.current) return
    ran.current = true

    const code = params.get('code')
    if (!code) {
      toast('카카오 로그인 코드가 없습니다.', 'error')
      navigate('/login', { replace: true })
      return
    }

    loginWithKakaoCode(code)
      .then(() => {
        const redirectTo = sessionStorage.getItem(POST_LOGIN_REDIRECT_KEY) || '/dashboard'
        sessionStorage.removeItem(POST_LOGIN_REDIRECT_KEY)
        toast('로그인되었습니다.', 'success')
        navigate(redirectTo, { replace: true })
      })
      .catch((err) => {
        toast(err.message || '로그인에 실패했습니다.', 'error')
        navigate('/login', { replace: true })
      })
  }, [params, loginWithKakaoCode, navigate])

  return <div className="page">로그인 처리 중...</div>
}
