// 테스트에서 "이 사용자로 로그인한 상태"를 만든다. 서명은 필요 없고 mock이 sub만 읽는다(api/workspaces.js의 meId).
import { resetMockWorkspaces } from '../api/workspaces'
import { resetMockLinks } from '../api/links'

const encode = (obj) => btoa(JSON.stringify(obj)).replace(/=+$/, '')

export function loginAs(userId) {
  sessionStorage.setItem(
    'linkshortener_access_token',
    `${encode({ alg: 'none' })}.${encode({ sub: userId })}.t`
  )
  sessionStorage.setItem('linkshortener_refresh_token', 'dev')
}

// mock은 모듈 안에 사본을 들고 있으므로, 테스트마다 시드로 되돌린다
export function resetMocks() {
  resetMockWorkspaces()
  resetMockLinks()
}
