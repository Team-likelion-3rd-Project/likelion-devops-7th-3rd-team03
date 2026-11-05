import { describe, expect, it } from 'vitest'
import { hasPermission, roleLabel } from './permissions'

describe('hasPermission', () => {
  it('관리자는 멤버·초대·워크스페이스 관리를 할 수 있다', () => {
    for (const p of ['member:manage', 'invite:manage', 'workspace:update', 'workspace:delete']) {
      expect(hasPermission('ADMIN', p)).toBe(true)
    }
  })

  it('멤버는 링크는 다루지만 팀 관리는 못 한다', () => {
    expect(hasPermission('MEMBER', 'link:create')).toBe(true)
    expect(hasPermission('MEMBER', 'link:delete')).toBe(true)
    expect(hasPermission('MEMBER', 'stats:read')).toBe(true)
    expect(hasPermission('MEMBER', 'member:manage')).toBe(false)
    expect(hasPermission('MEMBER', 'workspace:delete')).toBe(false)
  })

  it('역할이 없거나 서버가 모르는 역할이면 예외 없이 권한 없음이다', () => {
    expect(hasPermission(null, 'link:read')).toBe(false)
    expect(hasPermission(undefined, 'link:read')).toBe(false)
    expect(hasPermission('VIEWER', 'link:read')).toBe(false)
  })
})

describe('roleLabel', () => {
  it('알려진 역할은 한글, 모르는 역할은 그대로 보여 준다', () => {
    expect(roleLabel('ADMIN')).toBe('관리자')
    expect(roleLabel('MEMBER')).toBe('멤버')
    expect(roleLabel('VIEWER')).toBe('VIEWER')
  })
})
