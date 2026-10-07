import { useEffect, useRef, useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { resolveShortCode, recordClick } from '../mock/api'

// 이 페이지가 곧 "리다이렉트 서비스" 그 자체다.
// CloudFront -> ALB -> EC2 -> ElastiCache -> RDS 순으로 조회하고,
// 200이면 클릭 이벤트를 발행(SQS 대체)한 뒤 원본 URL로 이동한다.
export function RedirectHandler() {
  const { code } = useParams()
  const [phase, setPhase] = useState('loading') // loading | ready | not-found | gone
  const [trace, setTrace] = useState([])
  const [target, setTarget] = useState(null)
  const [reason, setReason] = useState(null)
  const [countdown, setCountdown] = useState(3)
  const clickRecorded = useRef(false)

  useEffect(() => {
    let cancelled = false
    setPhase('loading')
    resolveShortCode(code).then((res) => {
      if (cancelled) return
      setTrace(res.trace)
      if (res.status === 404) {
        setPhase('not-found')
      } else if (res.status === 410) {
        setPhase('gone')
        setReason(res.reason)
      } else {
        setTarget(res.link.original_url)
        setPhase('ready')
        if (!clickRecorded.current) {
          clickRecorded.current = true
          recordClick(code)
        }
      }
    })
    return () => {
      cancelled = true
    }
  }, [code])

  useEffect(() => {
    if (phase !== 'ready') return
    if (countdown <= 0) {
      window.location.href = target
      return
    }
    const t = setTimeout(() => setCountdown((c) => c - 1), 1000)
    return () => clearTimeout(t)
  }, [phase, countdown, target])

  return (
    <div className="page page--narrow">
      <div className="card redirect-card">
        <h1>/{code}</h1>

        <ol className="trace-list">
          {trace.map((step, i) => (
            <li key={i}>{step}</li>
          ))}
        </ol>

        {phase === 'loading' && <p className="redirect-status">조회 중...</p>}

        {phase === 'ready' && (
          <>
            <p className="redirect-status redirect-status--ok">
              200 OK · {countdown}초 후 이동합니다
            </p>
            <p className="redirect-target" title={target}>
              {target}
            </p>
            <button className="btn btn--primary" onClick={() => (window.location.href = target)}>
              지금 바로 이동
            </button>
          </>
        )}

        {phase === 'not-found' && (
          <>
            <p className="redirect-status redirect-status--error">404 Not Found</p>
            <p className="hero__sub">
              존재하지 않거나 삭제된 링크입니다. (삭제된 링크는 <code>deleted_at</code>이
              채워져 있어 코드가 영구 점유된 채로 조회에서 제외됩니다 — 재발급 시 피싱 벡터가
              되는 것을 막기 위함입니다)
            </p>
          </>
        )}

        {phase === 'gone' && (
          <>
            <p className="redirect-status redirect-status--error">410 Gone</p>
            <p className="hero__sub">
              {reason === 'EXPIRED'
                ? '만료된 링크입니다 (expires_at 경과).'
                : '소유자가 비활성화한 링크입니다 (is_active = 0).'}{' '}
              문서 6.13 미결정 사항 #5 — 이 데모는 404(존재하지 않음)와 410(존재했지만 접근
              불가)을 구분해서 응답합니다.
            </p>
          </>
        )}

        <Link to="/" className="btn btn--ghost" style={{ marginTop: 16 }}>
          단축기로 돌아가기
        </Link>
      </div>
    </div>
  )
}
