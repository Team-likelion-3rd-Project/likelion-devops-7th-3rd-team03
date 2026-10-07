import { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { createLink } from '../api/links'
import { ApiError } from '../api/http'
import { toast } from '../components/Toast'

export function Home() {
  const { user } = useAuth()
  const navigate = useNavigate()

  const [originalUrl, setOriginalUrl] = useState('')
  const [title, setTitle] = useState('')
  const [expiresAt, setExpiresAt] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [result, setResult] = useState(null)
  const [error, setError] = useState('')

  async function handleSubmit(e) {
    e.preventDefault()
    setError('')

    if (!user) {
      toast('링크를 만들려면 먼저 로그인하세요.', 'info')
      navigate('/login')
      return
    }

    if (!expiresAt) {
      setError('만료일은 필수입니다.')
      return
    }

    setSubmitting(true)
    try {
      const link = await createLink({
        originalUrl: originalUrl.trim(),
        title: title.trim() || undefined,
        expiresAt: new Date(expiresAt).toISOString(),
      })
      setResult(link)
      toast('단축 링크가 생성되었습니다.', 'success')
    } catch (err) {
      const message = err instanceof ApiError ? err.message : '알 수 없는 오류가 발생했습니다.'
      setError(message)
      toast(message, 'error')
    } finally {
      setSubmitting(false)
    }
  }

  const shortUrl = result?.shortUrl || ''

  return (
    <div className="page page--narrow">
      <section className="hero">
        <h1>링크를 짧고, 안전하고, 추적 가능하게</h1>
        <p className="hero__sub">
          긴 URL과 만료일을 입력하면 단축 링크를 즉시 발급합니다. 실제 백엔드(LinkController)에
          연동되어 있습니다.
        </p>
      </section>

      <form className="card shortener-form" onSubmit={handleSubmit}>
        <label className="field">
          <span>원본 URL</span>
          <input
            type="text"
            required
            placeholder="https://example.com/very/long/path?query=1"
            value={originalUrl}
            onChange={(e) => setOriginalUrl(e.target.value)}
          />
        </label>

        <label className="field">
          <span>제목 (선택)</span>
          <input
            type="text"
            placeholder="런칭 캠페인 링크"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            maxLength={100}
          />
        </label>

        <label className="field">
          <span>만료일 (필수)</span>
          <input
            type="datetime-local"
            required
            value={expiresAt}
            onChange={(e) => setExpiresAt(e.target.value)}
          />
        </label>

        {error && <p className="form-error">{error}</p>}

        <button className="btn btn--primary btn--block" type="submit" disabled={submitting}>
          {submitting ? '생성 중...' : '단축 링크 만들기'}
        </button>

        {!user && (
          <p className="form-hint">
            로그인하지 않아도 입력은 가능하지만, 발급 시 로그인 화면으로 이동합니다.
            (links.user_id는 NOT NULL이라 소유자가 반드시 필요합니다)
          </p>
        )}
      </form>

      {result && (
        <div className="card result-card">
          <div className="result-card__row">
            <a href={shortUrl} target="_blank" rel="noreferrer">
              {shortUrl}
            </a>
            <button
              className="btn btn--ghost"
              onClick={() => {
                navigator.clipboard.writeText(shortUrl)
                toast('클립보드에 복사했습니다.', 'success')
              }}
            >
              복사
            </button>
          </div>
          <p className="result-card__meta">
            원본: <span title={result.originalUrl}>{truncate(result.originalUrl, 60)}</span>
          </p>
          <p className="result-card__meta">
            <Link to="/dashboard">내 링크에서 확인하기</Link>
          </p>
        </div>
      )}
    </div>
  )
}

function truncate(str, n) {
  return str.length > n ? str.slice(0, n) + '…' : str
}
