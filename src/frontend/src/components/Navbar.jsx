import { NavLink, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'

export function Navbar() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  return (
    <header className="navbar">
      <div className="navbar__inner">
        <NavLink to="/" className="navbar__brand">
          Snipy
        </NavLink>
        <nav className="navbar__links">
          <NavLink to="/" end>
            단축하기
          </NavLink>
          {user && <NavLink to="/dashboard">내 링크</NavLink>}
          {user && <NavLink to="/stats">통계 대시보드</NavLink>}
        </nav>
        <div className="navbar__auth">
          {user ? (
            <>
              <span className="navbar__user">{user.userId.slice(0, 8)}</span>
              <button
                className="btn btn--ghost"
                onClick={async () => {
                  // 보호된 페이지(예: /dashboard)에 있을 때 logout()이 먼저 user를 null로
                  // 바꾸면 ProtectedRoute가 이 navigate보다 먼저 /login으로 튕겨버린다.
                  // 항상 홈으로 먼저 나간 뒤에 인증 상태를 정리한다.
                  navigate('/')
                  await logout()
                }}
              >
                로그아웃
              </button>
            </>
          ) : (
            <button className="btn btn--primary" onClick={() => navigate('/login')}>
              로그인
            </button>
          )}
        </div>
      </div>
    </header>
  )
}
