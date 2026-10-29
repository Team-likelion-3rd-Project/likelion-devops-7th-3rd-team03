import { Routes, Route } from 'react-router-dom'
import { AuthProvider } from './context/AuthContext'
import { Navbar } from './components/Navbar'
import { ToastHost } from './components/Toast'
import { ProtectedRoute } from './components/ProtectedRoute'

import { Home } from './pages/Home'
import { Login } from './pages/Login'
import { AuthCallback } from './pages/AuthCallback'
import { SignupProfile } from './pages/SignupProfile'
import { Dashboard } from './pages/Dashboard'
import { LinkStats } from './pages/LinkStats'
import { StatsDashboard } from './pages/StatsDashboard'
import { NotFound } from './pages/NotFound'

export default function App() {
  return (
    <AuthProvider>
      <div className="app-shell">
        <Navbar />
        <main className="app-main">
          <Routes>
            <Route path="/" element={<Home />} />
            <Route path="/login" element={<Login />} />
            <Route path="/auth/:provider/callback" element={<AuthCallback />} />
            <Route path="/signup/profile" element={<SignupProfile />} />
            <Route
              path="/dashboard"
              element={
                <ProtectedRoute>
                  <Dashboard />
                </ProtectedRoute>
              }
            />
            <Route
              path="/links/:id/stats"
              element={
                <ProtectedRoute>
                  <LinkStats />
                </ProtectedRoute>
              }
            />
            <Route
              path="/stats"
              element={
                <ProtectedRoute>
                  <StatsDashboard />
                </ProtectedRoute>
              }
            />
            <Route path="*" element={<NotFound />} />
          </Routes>
        </main>
        <ToastHost />
      </div>
    </AuthProvider>
  )
}
