// WorkspaceController(/api/workspaces/*) 연동. 백엔드(#200, #201)가 나오기 전까지는 mock으로 동작한다.
// VITE_WORKSPACE_API=real 이면 실제 API를 쓴다. 응답 필드 이름은 아직 확정되지 않았으니,
// 확정되면 아래 toWorkspace/toMember 만 고친다.
import { apiFetch, ApiError, getAccessToken } from './http'

// mock은 개발 서버(npm run dev)에서만 쓴다. 운영 빌드에는 들어가지 않는다.
const USE_MOCK = import.meta.env.DEV && import.meta.env.VITE_WORKSPACE_API !== 'real'

// workspace = { workspaceId, name, personal, role }  role: 'ADMIN' | 'MEMBER'
// member    = { userId, name, email, role, joinedAt }  (email 필드 제공 여부는 백엔드 확인 필요)
const toWorkspace = (w) => ({
  workspaceId: w.workspaceId,
  name: w.name,
  personal: w.personal ?? w.isPersonal ?? false,
  role: w.role
})
const toMember = (m) => ({
  userId: m.userId,
  name: m.name,
  email: m.email ?? '',
  role: m.role,
  joinedAt: m.joinedAt
})

const real = {
  list: async () => (await apiFetch('/workspaces')).data.map(toWorkspace),
  create: async (name) =>
    toWorkspace((await apiFetch('/workspaces', { method: 'POST', body: { name } })).data),
  rename: async (id, name) =>
    toWorkspace((await apiFetch(`/workspaces/${id}`, { method: 'PATCH', body: { name } })).data),
  remove: async (id) => {
    await apiFetch(`/workspaces/${id}`, { method: 'DELETE' })
  },
  members: async (id) => (await apiFetch(`/workspaces/${id}/members`)).data.map(toMember),
  changeRole: async (id, userId, role) =>
    toMember(
      (await apiFetch(`/workspaces/${id}/members/${userId}`, { method: 'PATCH', body: { role } }))
        .data
    ),
  removeMember: async (id, userId) => {
    await apiFetch(`/workspaces/${id}/members/${userId}`, { method: 'DELETE' })
  },
  leave: async (id) => {
    await apiFetch(`/workspaces/${id}/leave`, { method: 'POST' })
  },
  inviteCode: async (id) => (await apiFetch(`/workspaces/${id}/invite-code`)).data.inviteCode,
  reissueInviteCode: async (id) =>
    (await apiFetch(`/workspaces/${id}/invite-code`, { method: 'POST' })).data.inviteCode,
  join: async (code) =>
    toWorkspace(
      (await apiFetch('/workspaces/join', { method: 'POST', body: { inviteCode: code.trim() } }))
        .data
    )
}

// ---- mock: 규칙은 이슈 #200, #201 완료 조건을 따른다. ----
// 계정을 바꿔 가며 초대와 합류를 시험할 수 있도록 localStorage에 보관한다(새로고침과 로그아웃에도 남는다).
// 처음 상태로 되돌리려면 resetMockWorkspaces()를 부른다(로그인 화면의 개발용 버튼).

const STORE_KEY = 'snipy_mock_workspaces'

// 화면 표시용으로만 토큰을 디코딩한다 (AuthContext와 같은 방식).
function meId() {
  try {
    return JSON.parse(atob(getAccessToken().split('.')[1])).sub || 'me'
  } catch {
    return 'me'
  }
}

const daysAgo = (n) => new Date(Date.now() - n * 86_400_000).toISOString()

function randomInviteCode() {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789'
  return Array.from({ length: 10 }, () => chars[Math.floor(Math.random() * chars.length)]).join('')
}

// members 는 { userId, role, joinedAt } 만 갖고, 이름과 이메일은 users 에서 찾는다.
function seed() {
  const m = (userId, role, days) => ({ userId, role, joinedAt: daysAgo(days) })
  return {
    users: {
      'dev-admin': { name: '테스트 관리자', email: 'admin@snipy.test' },
      'dev-member': { name: '테스트 멤버', email: 'member@snipy.test' },
      u2: { name: '박서연', email: 'seoyeon@snipy.team' },
      u3: { name: '이도윤', email: 'doyoon@snipy.team' },
      u4: { name: '최하은', email: 'haeun@snipy.team' }
    },
    workspaces: [
      {
        workspaceId: 'ws-personal-dev-admin',
        name: '내 워크스페이스',
        personal: true,
        inviteCode: null,
        members: [m('dev-admin', 'ADMIN', 240)]
      },
      {
        workspaceId: 'ws-personal-dev-member',
        name: '내 워크스페이스',
        personal: true,
        inviteCode: null,
        members: [m('dev-member', 'ADMIN', 200)]
      },
      {
        workspaceId: 'ws-marketing',
        name: '마케팅팀',
        personal: false,
        inviteCode: 'Mk7Qx2pRt9',
        members: [
          m('dev-admin', 'ADMIN', 210),
          m('u2', 'MEMBER', 180),
          m('u3', 'MEMBER', 150),
          m('u4', 'ADMIN', 95)
        ]
      },
      {
        workspaceId: 'ws-events',
        name: '행사운영팀',
        personal: false,
        inviteCode: 'Ev3nTs8Kw4',
        members: [m('u3', 'ADMIN', 60), m('dev-admin', 'MEMBER', 30)]
      },
      {
        workspaceId: 'ws-design',
        name: '디자인팀',
        personal: false,
        inviteCode: 'Ds9gN4vLq2',
        members: [m('u2', 'ADMIN', 80), m('u4', 'MEMBER', 70)]
      }
    ]
  }
}

let db = null
function state() {
  // 매번 저장소에서 읽는다. 다른 탭(다른 테스트 계정)이 바꾼 내용을 바로 보기 위해서다.
  let stored = null
  try {
    stored = JSON.parse(localStorage.getItem(STORE_KEY))
  } catch {
    stored = null
  }
  if (stored) db = stored
  else if (!db) {
    db = seed()
    save()
  }
  // 처음 보는 사용자(실제 로그인 포함)에게는 개인 워크스페이스를 만들어 준다 (가입 시 자동 생성 규칙)
  const me = meId()
  if (!db.workspaces.some((w) => w.personal && w.members.some((x) => x.userId === me))) {
    db.users[me] ??= { name: '나', email: '' }
    db.workspaces.push({
      workspaceId: `ws-personal-${me}`,
      name: '내 워크스페이스',
      personal: true,
      inviteCode: null,
      members: [{ userId: me, role: 'ADMIN', joinedAt: new Date().toISOString() }]
    })
    save()
  }
  return db
}

function save() {
  try {
    localStorage.setItem(STORE_KEY, JSON.stringify(db))
  } catch {
    /* 저장하지 못해도 이 화면에서는 계속 동작한다 */
  }
}

export function resetMockWorkspaces() {
  db = null
  try {
    localStorage.removeItem(STORE_KEY)
  } catch {
    /* 저장소를 못 쓰면 지울 것도 없다 */
  }
}

const find = (id) => {
  const w = state().workspaces.find((x) => x.workspaceId === id)
  if (!w) throw new ApiError(404, '팀을 찾을 수 없어요.')
  return w
}
const mine = (w) => w.members.find((m) => m.userId === meId())
const asMember = (id) => {
  const w = find(id)
  if (!mine(w)) throw new ApiError(403, '이 팀의 멤버가 아니에요.')
  return w
}
const asAdmin = (id) => {
  const w = asMember(id)
  if (mine(w).role !== 'ADMIN') throw new ApiError(403, '관리자만 할 수 있어요.')
  return w
}
const adminCount = (w) => w.members.filter((m) => m.role === 'ADMIN').length
const view = (w) => toWorkspace({ ...w, role: mine(w).role })
const memberView = (m) => toMember({ ...m, ...state().users[m.userId] })
const target = (w, userId) => {
  const m = w.members.find((x) => x.userId === userId)
  if (!m) throw new ApiError(404, '멤버를 찾을 수 없어요.')
  return m
}

const mock = {
  list: async () => state().workspaces.filter(mine).map(view),
  create: async (name) => {
    const value = name.trim()
    if (!value) throw new ApiError(400, '이름을 입력해 주세요.')
    const w = {
      workspaceId: `ws-${Date.now()}`,
      name: value,
      personal: false,
      inviteCode: null, // 처음 조회할 때 발급된다
      members: [{ userId: meId(), role: 'ADMIN', joinedAt: new Date().toISOString() }]
    }
    state().workspaces.push(w)
    save()
    return view(w)
  },
  rename: async (id, name) => {
    const w = asAdmin(id)
    if (!name.trim()) throw new ApiError(400, '이름을 입력해 주세요.')
    w.name = name.trim()
    save()
    return view(w)
  },
  remove: async (id) => {
    const w = asAdmin(id)
    if (w.personal) throw new ApiError(409, '개인 팀은 삭제할 수 없어요.')
    if (w.members.length > 1) {
      throw new ApiError(
        409,
        '다른 멤버가 남아 있는 팀은 삭제할 수 없어요. 멤버를 먼저 내보내 주세요.'
      )
    }
    // state()는 부를 때마다 저장소를 새로 읽으니 객체 동일성이 아니라 id로 거른다
    const s = state()
    s.workspaces = s.workspaces.filter((x) => x.workspaceId !== w.workspaceId)
    save()
  },
  members: async (id) => asMember(id).members.map(memberView),
  changeRole: async (id, userId, role) => {
    const w = asAdmin(id)
    const m = target(w, userId)
    if (m.role === 'ADMIN' && role === 'MEMBER' && adminCount(w) === 1) {
      throw new ApiError(409, '관리자가 한 명뿐이라 멤버로 바꿀 수 없어요.')
    }
    m.role = role
    save()
    return memberView(m)
  },
  removeMember: async (id, userId) => {
    const w = asAdmin(id)
    const m = target(w, userId)
    if (m.role === 'ADMIN' && adminCount(w) === 1) {
      throw new ApiError(409, '마지막 관리자는 내보낼 수 없어요.')
    }
    w.members = w.members.filter((x) => x !== m)
    save()
  },
  leave: async (id) => {
    const w = asMember(id)
    if (w.personal) throw new ApiError(409, '개인 팀에서는 나갈 수 없어요.')
    if (mine(w).role === 'ADMIN' && adminCount(w) === 1) {
      throw new ApiError(409, '마지막 관리자는 나갈 수 없어요. 다른 멤버를 관리자로 지정해 주세요.')
    }
    w.members = w.members.filter((x) => x.userId !== meId())
    save()
  },
  inviteCode: async (id) => {
    const w = asAdmin(id)
    if (!w.inviteCode) {
      w.inviteCode = randomInviteCode()
      save()
    }
    return w.inviteCode
  },
  // 재발급하면 이전 코드로는 합류할 수 없다
  reissueInviteCode: async (id) => {
    const w = asAdmin(id)
    w.inviteCode = randomInviteCode()
    save()
    return w.inviteCode
  },
  join: async (code) => {
    const value = code.trim()
    if (!value) throw new ApiError(400, '초대 코드를 입력해 주세요.')
    const w = state().workspaces.find((x) => x.inviteCode === value)
    if (!w)
      throw new ApiError(404, '코드가 맞지 않아요. 관리자에게 받은 코드를 다시 확인해 주세요.')
    if (mine(w)) throw new ApiError(409, '이미 참여 중인 팀이에요.')
    w.members.push({ userId: meId(), role: 'MEMBER', joinedAt: new Date().toISOString() })
    save()
    return view(w)
  }
}

const api = USE_MOCK ? mock : real

export const listWorkspaces = () => api.list()
export const createWorkspace = (name) => api.create(name)
export const renameWorkspace = (id, name) => api.rename(id, name)
export const deleteWorkspace = (id) => api.remove(id)
export const listMembers = (id) => api.members(id)
export const changeMemberRole = (id, userId, role) => api.changeRole(id, userId, role)
export const removeMember = (id, userId) => api.removeMember(id, userId)
export const leaveWorkspace = (id) => api.leave(id)
export const getInviteCode = (id) => api.inviteCode(id)
export const reissueInviteCode = (id) => api.reissueInviteCode(id)
export const joinWorkspace = (code) => api.join(code)
