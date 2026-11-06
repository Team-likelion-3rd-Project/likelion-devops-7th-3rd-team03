import { Routes, Route } from 'react-router-dom'
import { AuthProvider, useAuth } from './context/AuthContext'
import { WorkspaceProvider } from './context/WorkspaceContext'
import { AppShell, GuestShell } from './components/AppShell'
import { ProtectedRoute } from './components/ProtectedRoute'

import { Home } from './pages/Home'
import { Login } from './pages/Login'
import { AuthCallback } from './pages/AuthCallback'
import { SignupProfile } from './pages/SignupProfile'
import { Dashboard } from './pages/Dashboard'
import { LinkStats } from './pages/LinkStats'
import { StatsDashboard } from './pages/StatsDashboard'
import { Members } from './pages/Members'
import { Settings } from './pages/Settings'
import { NotFound } from './pages/NotFound'

// 로그인하면 상단바·사이드 메뉴 뼈대를, 아니면 같은 모양의 상단바만 있는 뼈대를 쓴다
function Layout() {
  const { user } = useAuth()
  return user ? <AppShell /> : <GuestShell />
}

const guarded = (page) => <ProtectedRoute>{page}</ProtectedRoute>

export default function App() {
  return (
    <AuthProvider>
      <WorkspaceProvider>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/" element={<Home />} />
            <Route path="/login" element={<Login />} />
            <Route path="/auth/:provider/callback" element={<AuthCallback />} />
            <Route path="/signup/profile" element={<SignupProfile />} />
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
