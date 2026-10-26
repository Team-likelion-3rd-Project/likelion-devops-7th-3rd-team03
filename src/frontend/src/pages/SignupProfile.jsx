import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { toast } from '../components/Toast'
import { Avatar } from '../components/Avatar'
import { POST_LOGIN_REDIRECT_KEY } from './Login'

const NICKNAME_MAX = 50
const EMAIL_MAX = 100
const IMAGE_URL_MAX = 500 // 백엔드 ProfileImageUrlPolicy와 같은 규칙: https:// 로 시작, 500자 이하
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
// 가입 API(POST /api/auth/signup) 오류: 410 가입 토큰 만료·이미 사용됨, 403 탈퇴 상태의 기존 계정. 둘 다 로그인 화면으로 보낸다.
// 400(입력 검증 실패)은 세션이 소모되지 않으므로 같은 토큰으로 다시 제출할 수 있다.
const SESSION_EXPIRED_STATUS = 410
const WITHDRAWN_STATUS = 403

function isAllowedImageUrl(url) {
  return url.startsWith('https://') && url.length <= IMAGE_URL_MAX
}

function validate({ nickname, email, profileImageUrl }) {
  const errors = {}
  if (!nickname) errors.nickname = '닉네임을 입력해주세요.'
  else if (nickname.length > NICKNAME_MAX)
    errors.nickname = `닉네임은 ${NICKNAME_MAX}자 이하로 입력해주세요.`

  if (!email) errors.email = '이메일을 입력해주세요.'
  else if (email.length > EMAIL_MAX || !EMAIL_PATTERN.test(email))
    errors.email = '올바른 이메일 형식이 아닙니다.'

  if (profileImageUrl && !isAllowedImageUrl(profileImageUrl)) {
    errors.profileImageUrl = `이미지 주소는 https:// 로 시작하고 ${IMAGE_URL_MAX}자 이하여야 합니다.`
  }
  return errors
}

// 신규 소셜 사용자가 닉네임·이메일·프로필 이미지를 입력해 가입을 완료한다.
export function SignupProfile() {
  const { pendingSignup, signup, cancelSignup } = useAuth()
  const navigate = useNavigate()
  const finished = useRef(false) // 가입 성공 직후 pendingSignup이 비워지며 로그인 화면으로 튕기는 것을 막는다

  const prefill = pendingSignup?.prefill || {}
  const [form, setForm] = useState({
    nickname: prefill.nickname || '',
    email: prefill.email || '',
    profileImageUrl: prefill.profileImageUrl || ''
  })
  const [errors, setErrors] = useState({})
  const [serverError, setServerError] = useState('')
  const [submitting, setSubmitting] = useState(false)

  // 가입 필요 응답 없이 들어오면(직접 주소 입력, 세션 소진) 로그인 화면으로 보낸다.
  useEffect(() => {
    if (!pendingSignup && !finished.current) navigate('/login', { replace: true })
  }, [pendingSignup, navigate])

  if (!pendingSignup) return null

  const update = (key) => (e) => setForm((f) => ({ ...f, [key]: e.target.value }))
  const imageUrl = form.profileImageUrl.trim()

  async function handleSubmit(e) {
    e.preventDefault()
    setServerError('')

    const values = {
      nickname: form.nickname.trim(),
      email: form.email.trim(),
      profileImageUrl: form.profileImageUrl.trim()
    }
    const found = validate(values)
    setErrors(found)
    if (Object.keys(found).length > 0) return

    setSubmitting(true)
    finished.current = true
    try {
      await signup({ ...values, profileImageUrl: values.profileImageUrl || null })
      const redirectTo = sessionStorage.getItem(POST_LOGIN_REDIRECT_KEY) || '/dashboard'
      sessionStorage.removeItem(POST_LOGIN_REDIRECT_KEY)
      toast('가입이 완료되었습니다.', 'success')
      navigate(redirectTo, { replace: true })
    } catch (err) {
      if (err.status === SESSION_EXPIRED_STATUS || err.status === WITHDRAWN_STATUS) {
        cancelSignup()
        toast(
          err.status === SESSION_EXPIRED_STATUS
            ? '가입 시간이 지났습니다. 다시 로그인해주세요.'
            : '탈퇴한 계정입니다.', // 403의 서버 메시지에는 사용자 ID가 들어 있어서 쓰지 않는다
          'error'
        )
        navigate('/login', { replace: true })
        return
      }
      finished.current = false
      setServerError(err.message || '가입에 실패했습니다. 잠시 후 다시 시도해주세요.')
      setSubmitting(false)
    }
  }

  return (
    <div className="page page--narrow">
      <div className="card login-card">
        <h1>추가 정보 입력</h1>
        <p className="hero__sub">서비스를 시작하려면 아래 정보를 확인해주세요.</p>

        <form className="login-form" onSubmit={handleSubmit} noValidate>
          <div className="signup-avatar">
            {/* 주소가 비었거나 https가 아니면 src 없이 넘겨 기본 아이콘을 보여준다. 불러오지 못하면 Avatar가 기본 아이콘으로 바꾼다. */}
            <Avatar
              src={isAllowedImageUrl(imageUrl) ? imageUrl : ''}
              size={88}
              alt="프로필 이미지 미리보기"
            />
          </div>

          <label className="field">
            <span>닉네임 (필수)</span>
            <input
              value={form.nickname}
              onChange={update('nickname')}
              maxLength={NICKNAME_MAX + 20}
              autoComplete="nickname"
            />
          </label>
          {errors.nickname && <p className="form-error">{errors.nickname}</p>}

          <label className="field">
            <span>이메일 (필수)</span>
            <input
              type="email"
              value={form.email}
              onChange={update('email')}
              autoComplete="email"
            />
          </label>
          {errors.email && <p className="form-error">{errors.email}</p>}

          <label className="field">
            <span>프로필 이미지 주소 (선택)</span>
            <input
              type="url"
              value={form.profileImageUrl}
              onChange={update('profileImageUrl')}
              placeholder="https://"
            />
          </label>
          {errors.profileImageUrl && <p className="form-error">{errors.profileImageUrl}</p>}

          {serverError && (
            <p className="form-error" role="alert">
              {serverError}
            </p>
          )}

          <button className="btn btn--primary btn--block" type="submit" disabled={submitting}>
            {submitting ? '가입 중...' : '가입 완료'}
          </button>
          <p className="form-hint">
            15분 안에 완료해주세요. 시간이 지나면 처음부터 다시 로그인해야 합니다.
          </p>
        </form>
      </div>
    </div>
  )
}
