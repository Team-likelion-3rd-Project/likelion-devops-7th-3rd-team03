// 워크스페이스 mock이 #200, #201 완료 조건의 규칙을 지키는지 본다. 실제 백엔드 API가 붙으면 이 규칙이 서버로 넘어간다.
import { beforeEach, describe, expect, it } from 'vitest'
import {
  changeMemberRole,
  createWorkspace,
  deleteWorkspace,
  getInviteCode,
  joinWorkspace,
  leaveWorkspace,
  listMembers,
  listWorkspaces,
  reissueInviteCode,
  removeMember,
  renameWorkspace
} from './workspaces'
import { loginAs, resetMocks } from '../test/helpers'

const ids = async () => (await listWorkspaces()).map((w) => w.workspaceId)

beforeEach(() => {
  resetMocks()
  loginAs('dev-admin')
})

describe('목록', () => {
  it('내 개인 팀과 소속 팀을 역할과 함께 보여 준다', async () => {
    const list = await listWorkspaces()
    const byName = Object.fromEntries(list.map((w) => [w.name, w]))
    expect(byName['내 워크스페이스']).toMatchObject({ personal: true, role: 'ADMIN' })
    expect(byName['마케팅팀']).toMatchObject({ personal: false, role: 'ADMIN' })
    expect(byName['행사운영팀']).toMatchObject({ personal: false, role: 'MEMBER' })
    expect(list.some((w) => w.name === '디자인팀')).toBe(false)
  })

  it('처음 보는 사용자에게는 개인 팀을 하나 만들어 준다', async () => {
    loginAs('brand-new-user')
    const list = await listWorkspaces()
    expect(list).toHaveLength(1)
    expect(list[0]).toMatchObject({ name: '내 워크스페이스', personal: true, role: 'ADMIN' })
  })
})

describe('생성과 이름 변경', () => {
  it('이름의 앞뒤 공백을 지우고 만든 사람을 관리자로 둔다', async () => {
    const w = await createWorkspace('  우리 팀  ')
    expect(w).toMatchObject({ name: '우리 팀', personal: false, role: 'ADMIN' })
    expect(await ids()).toContain(w.workspaceId)
  })

  it('빈 이름은 400이다', async () => {
    await expect(createWorkspace('   ')).rejects.toMatchObject({ status: 400 })
  })

  it('관리자만 이름을 바꾼다', async () => {
    const renamed = await renameWorkspace('ws-marketing', '마케팅 본부')
    expect(renamed.name).toBe('마케팅 본부')
    await expect(renameWorkspace('ws-events', '바꿔볼래')).rejects.toMatchObject({ status: 403 })
  })
})

describe('삭제', () => {
  it('멤버가 없는 팀은 삭제되고 목록에서 사라진다', async () => {
    const w = await createWorkspace('임시 팀')
    await deleteWorkspace(w.workspaceId)
    expect(await ids()).not.toContain(w.workspaceId)
  })

  it('다른 멤버가 남은 팀은 409다', async () => {
    await expect(deleteWorkspace('ws-marketing')).rejects.toMatchObject({ status: 409 })
    expect(await ids()).toContain('ws-marketing')
  })

  it('개인 팀은 삭제할 수 없다', async () => {
    await expect(deleteWorkspace('ws-personal-dev-admin')).rejects.toMatchObject({ status: 409 })
  })

  it('관리자가 아니면 403이다', async () => {
    await expect(deleteWorkspace('ws-events')).rejects.toMatchObject({ status: 403 })
  })
})

describe('멤버 관리', () => {
  it('멤버 목록에는 이름과 이메일이 붙는다', async () => {
    const members = await listMembers('ws-marketing')
    expect(members).toHaveLength(4)
    expect(members.find((m) => m.userId === 'dev-admin')).toMatchObject({
      name: '테스트 관리자',
      email: 'admin@snipy.test',
      role: 'ADMIN'
    })
  })

  it('팀 멤버가 아니면 볼 수 없다', async () => {
    loginAs('dev-member')
    await expect(listMembers('ws-marketing')).rejects.toMatchObject({ status: 403 })
  })

  it('역할을 바꾸고 멤버를 내보낸다', async () => {
    await changeMemberRole('ws-marketing', 'u2', 'ADMIN')
    expect((await listMembers('ws-marketing')).find((m) => m.userId === 'u2').role).toBe('ADMIN')
    await removeMember('ws-marketing', 'u3')
    expect((await listMembers('ws-marketing')).some((m) => m.userId === 'u3')).toBe(false)
  })

  it('멤버는 역할 변경과 내보내기를 할 수 없다', async () => {
    await expect(changeMemberRole('ws-events', 'u3', 'MEMBER')).rejects.toMatchObject({
      status: 403
    })
    await expect(removeMember('ws-events', 'u3')).rejects.toMatchObject({ status: 403 })
  })

  it('마지막 관리자는 강등·내보내기·나가기 모두 막힌다', async () => {
    const w = await createWorkspace('외톨이 팀')
    await expect(changeMemberRole(w.workspaceId, 'dev-admin', 'MEMBER')).rejects.toMatchObject({
      status: 409
    })
    await expect(removeMember(w.workspaceId, 'dev-admin')).rejects.toMatchObject({ status: 409 })
    await expect(leaveWorkspace(w.workspaceId)).rejects.toMatchObject({ status: 409 })
  })

  it('관리자가 둘이면 한 명은 나갈 수 있다', async () => {
    await leaveWorkspace('ws-marketing')
    expect(await ids()).not.toContain('ws-marketing')
  })

  it('개인 팀에서는 나갈 수 없다', async () => {
    await expect(leaveWorkspace('ws-personal-dev-admin')).rejects.toMatchObject({ status: 409 })
  })
})

describe('초대 코드와 합류', () => {
  it('코드는 처음 조회할 때 발급되고 이후 같은 값이다', async () => {
    const w = await createWorkspace('새 팀')
    const first = await getInviteCode(w.workspaceId)
    expect(first).toMatch(/^[A-Za-z0-9]{10}$/)
    expect(await getInviteCode(w.workspaceId)).toBe(first)
  })

  it('관리자만 코드를 본다', async () => {
    await expect(getInviteCode('ws-events')).rejects.toMatchObject({ status: 403 })
  })

  it('코드로 합류하면 멤버 역할이고, 이미 참여 중이면 409다', async () => {
    loginAs('dev-member')
    const w = await joinWorkspace('Mk7Qx2pRt9')
    expect(w).toMatchObject({ name: '마케팅팀', role: 'MEMBER' })
    await expect(joinWorkspace('Mk7Qx2pRt9')).rejects.toMatchObject({ status: 409 })
  })

  it('앞뒤 공백은 무시하고, 틀린 코드는 404, 빈 코드는 400이다', async () => {
    loginAs('dev-member')
    await expect(joinWorkspace('nope')).rejects.toMatchObject({ status: 404 })
    await expect(joinWorkspace('  ')).rejects.toMatchObject({ status: 400 })
    await expect(joinWorkspace('  Mk7Qx2pRt9 ')).resolves.toMatchObject({ name: '마케팅팀' })
  })

  it('재발급하면 이전 코드로는 합류할 수 없다', async () => {
    const next = await reissueInviteCode('ws-marketing')
    expect(next).not.toBe('Mk7Qx2pRt9')
    loginAs('dev-member')
    await expect(joinWorkspace('Mk7Qx2pRt9')).rejects.toMatchObject({ status: 404 })
    await expect(joinWorkspace(next)).resolves.toMatchObject({ name: '마케팅팀' })
  })

  it('다른 계정(다른 탭)에서 합류한 사람이 관리자 쪽 멤버 목록에 보인다', async () => {
    loginAs('dev-member')
    await joinWorkspace('Mk7Qx2pRt9')
    loginAs('dev-admin')
    const members = await listMembers('ws-marketing')
    expect(members.map((m) => m.userId)).toContain('dev-member')
  })
})
