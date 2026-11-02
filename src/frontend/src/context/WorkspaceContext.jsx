import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import { useAuth } from './AuthContext'
import { listWorkspaces } from '../api/workspaces'
import { hasPermission } from '../permissions'

const WorkspaceContext = createContext(null)
const SELECTED_KEY = 'linkshortener_workspace_id'

// 선택한 팀은 새로고침해도 유지한다. 계정을 바꿔도(또는 탭마다 다른 계정이어도) 섞이지 않도록 사용자별로 저장한다.
// 저장소를 못 써도 화면은 동작해야 한다.
function loadSelected(userId) {
  try {
    return localStorage.getItem(`${SELECTED_KEY}:${userId}`)
  } catch {
    return null
  }
}

function saveSelected(id, userId) {
  try {
    localStorage.setItem(`${SELECTED_KEY}:${userId}`, id)
  } catch {
    /* 저장하지 못해도 현재 화면에는 영향이 없다 */
  }
}

export function WorkspaceProvider({ children }) {
  const { user } = useAuth()
  const userId = user?.userId
  const [workspaces, setWorkspaces] = useState([])
  const [selectedId, setSelectedId] = useState(() => loadSelected(userId))
  // idle(로그인 전) | loading | ready | error
  const [status, setStatus] = useState('idle')

  // preferId 가 있으면 그 팀을 선택한다 (방금 만들거나 합류한 팀). 목록에 없으면 첫 팀으로 되돌린다.
  // 이미 목록이 떠 있을 때는 화면을 '불러오는 중'으로 되돌리지 않고 조용히 갱신한다.
  const refresh = useCallback(
    async (preferId) => {
      setStatus((s) => (s === 'ready' ? s : 'loading'))
      try {
        const list = await listWorkspaces()
        setWorkspaces(list)
        setSelectedId((prev) => {
          const want = preferId ?? loadSelected(userId) ?? prev
          const id = list.some((w) => w.workspaceId === want)
            ? want
            : (list[0]?.workspaceId ?? null)
          if (id) saveSelected(id, userId)
          return id
        })
        setStatus('ready')
      } catch {
        setStatus('error')
      }
    },
    [userId]
  )

  useEffect(() => {
    if (userId) {
      refresh()
    } else {
      setWorkspaces([])
      setStatus('idle')
    }
  }, [userId, refresh])

  // 다른 탭에서 멤버가 바뀌었을 수 있으니 이 창으로 돌아오면 다시 불러온다
  useEffect(() => {
    if (!userId) return
    const onFocus = () => refresh()
    window.addEventListener('focus', onFocus)
    return () => window.removeEventListener('focus', onFocus)
  }, [userId, refresh])

  const select = useCallback(
    (id) => {
      setSelectedId(id)
      saveSelected(id, userId)
    },
    [userId]
  )

  const current = workspaces.find((w) => w.workspaceId === selectedId) ?? null
  const role = current?.role ?? null

  return (
    <WorkspaceContext.Provider
      value={{
        workspaces,
        current,
        role,
        can: (permission) => hasPermission(role, permission),
        status,
        select,
        refresh
      }}
    >
      {children}
    </WorkspaceContext.Provider>
  )
}

export function useWorkspace() {
  const ctx = useContext(WorkspaceContext)
  if (!ctx) throw new Error('useWorkspace must be used within WorkspaceProvider')
  return ctx
}
