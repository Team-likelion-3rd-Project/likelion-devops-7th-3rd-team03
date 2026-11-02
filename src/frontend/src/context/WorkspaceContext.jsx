import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react'
import { useAuth } from './AuthContext'
import { listWorkspaces } from '../api/workspaces'
import { listLinks } from '../api/links'
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
        // 포커스마다 다시 불러와도 내용이 같으면 객체를 그대로 둔다. 그래야 이 값을 쓰는 화면이 불필요하게 다시 불러오지 않는다.
        setWorkspaces((prev) => (JSON.stringify(prev) === JSON.stringify(list) ? prev : list))
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

  // 사이드바 "링크" 옆 개수. 못 가져오면 null로 두고 숨긴다. 링크를 만들거나 지운 화면이 refreshLinkCount로 갱신한다.
  const [linkCount, setLinkCount] = useState(null)
  const currentId = current?.workspaceId
  const countRequest = useRef(0)
  const refreshLinkCount = useCallback(() => {
    if (!current) return
    // 팀을 빠르게 바꾸면 이전 팀의 응답이 늦게 도착할 수 있어, 가장 마지막 요청의 응답만 쓴다
    const request = ++countRequest.current
    listLinks(current, 0, 1)
      .then((res) => request === countRequest.current && setLinkCount(res.pagination.totalElements))
      .catch(() => request === countRequest.current && setLinkCount(null))
    // current 객체가 아니라 id·개인 여부가 바뀔 때만 다시 만든다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentId, current?.personal])

  useEffect(() => {
    setLinkCount(null)
    refreshLinkCount()
  }, [refreshLinkCount])

  return (
    <WorkspaceContext.Provider
      value={{
        workspaces,
        current,
        role,
        can: (permission) => hasPermission(role, permission),
        status,
        select,
        refresh,
        linkCount,
        refreshLinkCount
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
