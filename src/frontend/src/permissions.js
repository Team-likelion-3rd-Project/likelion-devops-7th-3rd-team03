// 권한은 워크스페이스 안에서의 역할로만 정해진다. 링크를 누가 만들었는지는 보지 않는다.
// 서버가 최종 판단하고, 여기서는 화면에서 버튼을 보일지 말지만 정한다.

export const ROLES = ['ADMIN', 'MEMBER']

export const ROLE_LABEL = {
  ADMIN: '관리자',
  MEMBER: '멤버'
}

export const ROLE_DESCRIPTION = {
  ADMIN: '멤버·초대 코드·워크스페이스 관리를 포함한 모든 권한',
  MEMBER: '링크 생성, 수정, 삭제'
}

const MEMBER = ['stats:read', 'link:read', 'link:create', 'link:update', 'link:delete']
const ADMIN = [...MEMBER, 'member:manage', 'invite:manage', 'workspace:update', 'workspace:delete']

const PERMISSIONS = {
  ADMIN: new Set(ADMIN),
  MEMBER: new Set(MEMBER)
}

// 서버가 모르는 역할(예: 이후에 추가될 VIEWER)을 보내도 화면이 멈추지 않게, 권한은 없는 것으로 본다.
export function hasPermission(role, permission) {
  return PERMISSIONS[role]?.has(permission) ?? false
}

export const roleLabel = (role) => ROLE_LABEL[role] ?? role
