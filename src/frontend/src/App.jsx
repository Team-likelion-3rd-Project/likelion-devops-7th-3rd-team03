import { lazy, Suspense } from 'react'
import { Routes, Route } from 'react-router-dom'
import Spinner from '@cloudscape-design/components/spinner'
import { AuthProvider, useAuth } from './context/AuthContext'
import { WorkspaceProvider } from './context/WorkspaceContext'
import { AppShell, GuestShell } from './components/AppShell'
import { ProtectedRoute } from './components/ProtectedRoute'

import { Home } from './pages/Home'
import { Login } from './pages/Login'
import { NotFound } from './pages/NotFound'

// 처음 화면(홈·로그인)에 필요 없는 페이지는 들어갈 때 내려받는다. 통계 화면과 홈의 운영 현황(Cloudscape 차트)이 첫 화면 번들에서 빠진다.
const load = (name, importer) => lazy(() => importer().then((m) => ({ default: m[name] })))
const AuthCallback = load('AuthCallback', () => import('./pages/AuthCallback'))
const SignupProfile = load('SignupProfile', () => import('./pages/SignupProfile'))
const Dashboard = load('Dashboard', () => import('./pages/Dashboard'))
const LinkStats = load('LinkStats', () => import('./pages/LinkStats'))
const StatsDashboard = load('StatsDashboard', () => import('./pages/StatsDashboard'))
const Members = load('Members', () => import('./pages/Members'))
const Settings = load('Settings', () => import('./pages/Settings'))

// 상단바·메뉴는 그대로 두고 본문 자리에서만 기다린다
const lazyPage = (el) => <Suspense fallback={<Spinner size="large" />}>{el}</Suspense>

// 로그인하면 상단바·사이드 메뉴 뼈대를, 아니면 같은 모양의 상단바만 있는 뼈대를 쓴다
function Layout() {
  const { user } = useAuth()
  return user ? <AppShell /> : <GuestShell />
}

const guarded = (el) => <ProtectedRoute>{lazyPage(el)}</ProtectedRoute>

export default function App() {
  return (
    <AuthProvider>
      <WorkspaceProvider>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/" element={<Home />} />
            <Route path="/login" element={<Login />} />
            <Route path="/auth/:provider/callback" element={lazyPage(<AuthCallback />)} />
            <Route path="/signup/profile" element={lazyPage(<SignupProfile />)} />
            <Route path="/dashboard" element={guarded(<Dashboard />)} />
            <Route path="/links/:id/stats" element={guarded(<LinkStats />)} />
            <Route path="/stats" element={guarded(<StatsDashboard />)} />
            <Route path="/members" element={guarded(<Members />)} />
            <Route path="/settings" element={guarded(<Settings />)} />
            <Route path="*" element={<NotFound />} />
          </Route>
        </Routes>
      </WorkspaceProvider>
    </AuthProvider>
  )
}
