import { Link } from 'react-router-dom'

// 정의되지 않은 경로. 단축 링크(/:code) 리다이렉트는 백엔드가 처리하므로 SPA가 받을 일이 없다.
export function NotFound() {
  return (
    <div className="page page--narrow">
      <div className="card redirect-card">
        <h1>페이지를 찾을 수 없습니다</h1>
        <p className="hero__sub">주소가 잘못되었거나 이동된 페이지입니다.</p>
        <Link to="/" className="btn btn--primary" style={{ marginTop: 16 }}>
          홈으로 돌아가기
        </Link>
      </div>
    </div>
  )
}
