// 소셜 로그인 provider 설정과 인가 URL 빌더.
// 로그인 화면에 보일 provider는 VITE_SOCIAL_PROVIDERS(쉼표 구분, 예: "kakao,google")로 정한다. 비우면 kakao만 보인다.
// 각 provider의 client id / redirect URI는 백엔드 설정과 같은 값이어야 토큰 교환이 성공한다.
const env = import.meta.env

const PROVIDERS = {
  kakao: {
    label: '카카오',
    clientId: env.VITE_KAKAO_CLIENT_ID,
    redirectUri: env.VITE_KAKAO_REDIRECT_URI,
    authorizeUrl: 'https://kauth.kakao.com/oauth/authorize'
  },
  google: {
    label: '구글',
    clientId: env.VITE_GOOGLE_CLIENT_ID,
    redirectUri: env.VITE_GOOGLE_REDIRECT_URI,
    authorizeUrl: 'https://accounts.google.com/o/oauth2/v2/auth',
    scope: 'openid email profile'
  },
  naver: {
    label: '네이버',
    clientId: env.VITE_NAVER_CLIENT_ID,
    redirectUri: env.VITE_NAVER_REDIRECT_URI,
    authorizeUrl: 'https://nid.naver.com/oauth2.0/authorize'
  }
}

export function enabledProviders() {
  const ids = (env.VITE_SOCIAL_PROVIDERS || 'kakao')
    .split(',')
    .map((s) => s.trim().toLowerCase())
    .filter((id) => PROVIDERS[id])
  return [...new Set(ids)].map((id) => ({ id, ...PROVIDERS[id] }))
}

export function getProvider(id) {
  return enabledProviders().find((p) => p.id === id) || null
}

export function isConfigured(provider) {
  return Boolean(provider.clientId && provider.redirectUri)
}

export function buildAuthorizeUrl(provider, state) {
  const url = new URL(provider.authorizeUrl)
  url.searchParams.set('client_id', provider.clientId)
  url.searchParams.set('redirect_uri', provider.redirectUri)
  url.searchParams.set('response_type', 'code')
  url.searchParams.set('state', state)
  if (provider.scope) url.searchParams.set('scope', provider.scope)
  return url.toString()
}

// 로그인 요청마다 만든 무작위 state를 인가 서버 왕복 동안 sessionStorage에 보관하고, 콜백에서 대조한다 (CSRF 방지).
const STATE_KEY = 'linkshortener_oauth_state'

export function createState(providerId) {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  const state = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  sessionStorage.setItem(STATE_KEY, JSON.stringify({ provider: providerId, state }))
  return state
}

// 한 번 꺼내면 지운다 (재사용 방지). provider와 state가 모두 일치할 때만 true.
export function consumeState(providerId, state) {
  const raw = sessionStorage.getItem(STATE_KEY)
  sessionStorage.removeItem(STATE_KEY)
  if (!raw || !state) return false
  try {
    const saved = JSON.parse(raw)
    return saved.provider === providerId && saved.state === state
  } catch {
    return false
  }
}

// 로그아웃 때 어느 provider로 로그인했는지 알아야 해서 로그인 성공 시 저장한다.
const PROVIDER_KEY = 'linkshortener_provider'

export function getStoredProvider() {
  return localStorage.getItem(PROVIDER_KEY)
}

export function setStoredProvider(providerId) {
  localStorage.setItem(PROVIDER_KEY, providerId)
}

export function clearStoredProvider() {
  localStorage.removeItem(PROVIDER_KEY)
}
