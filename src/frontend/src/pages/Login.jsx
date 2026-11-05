import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import Alert from '@cloudscape-design/components/alert'
import Box from '@cloudscape-design/components/box'
import Button from '@cloudscape-design/components/button'
import Container from '@cloudscape-design/components/container'
import ContentLayout from '@cloudscape-design/components/content-layout'
import Header from '@cloudscape-design/components/header'
import SpaceBetween from '@cloudscape-design/components/space-between'
import { useAuth } from '../context/AuthContext'
import { enabledProviders, isConfigured, buildAuthorizeUrl, createState } from '../auth/providers'
import { DEV_ACCOUNTS } from '../auth/devLogin'
import { resetMockWorkspaces } from '../api/workspaces'
import { resetMockLinks } from '../api/links'
import { toast } from '../components/Toast'

export const POST_LOGIN_REDIRECT_KEY = 'linkshortener_post_login_redirect'

export function Login() {
  const { user, loading, loginAsDev } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const providers = enabledProviders()

  function handleLogin(provider) {
    // 인증 페이지로 벗어났다 돌아오므로 라우터 state가 아니라 sessionStorage로 목적지를 넘긴다.
    sessionStorage.setItem(POST_LOGIN_REDIRECT_KEY, location.state?.from || '/')
    window.location.href = buildAuthorizeUrl(provider, createState(provider.id))
  }

  // 이미 로그인했다면 로그인 화면을 보여 줄 이유가 없어서 홈으로 보낸다(뒤로 가기·주소 직접 입력으로 올 수 있다)
  if (user) return <Navigate to="/" replace />

  return (
    <ContentLayout header={<Header variant="h1">로그인</Header>}>
      <SpaceBetween size="l">
        <Container
          header={
            <Header variant="h2" description="소셜 계정으로 로그인합니다.">
              시작하기
            </Header>
          }
        >
          <SpaceBetween size="m">
            {providers.map((p) => (
              <SpaceBetween key={p.id} size="xs">
                <Button
                  variant="primary"
                  fullWidth
                  loading={loading}
                  disabled={!isConfigured(p)}
                  onClick={() => handleLogin(p)}
                >
                  {p.label}로 로그인
                </Button>
                {!isConfigured(p) && (
                  <Alert type="warning">
                    VITE_{p.id.toUpperCase()}_CLIENT_ID / VITE_{p.id.toUpperCase()}
                    _REDIRECT_URI가 설정되지 않았습니다. 프론트엔드 <code>.env</code>를
                    확인해주세요.
                  </Alert>
                )}
              </SpaceBetween>
            ))}
          </SpaceBetween>
        </Container>

        {import.meta.env.DEV && (
          <Container
            header={
              <Header
                variant="h2"
                description="개발 서버에서만 보여요. 백엔드 없이 팀 초대·내보내기를 시험하는 가짜 로그인이에요."
              >
                개발용 테스트 계정
              </Header>
            }
          >
            <SpaceBetween size="m">
              <SpaceBetween direction="horizontal" size="xs">
                {DEV_ACCOUNTS.map((a) => (
                  <Button
                    key={a.userId}
                    onClick={() => {
                      loginAsDev(a)
                      navigate(location.state?.from || '/members', { replace: true })
                    }}
                  >
                    {a.name}로 로그인
                  </Button>
                ))}
              </SpaceBetween>
              <Box color="text-body-secondary" fontSize="body-s">
                테스트 관리자는 마케팅팀의 관리자이고, 테스트 멤버는 어느 팀에도 속해 있지 않아요.
                로그인은 탭마다 따로예요. 탭 하나는 관리자로, 다른 탭은 멤버로 로그인해 나란히 두고
                시험하세요(팀 데이터는 탭끼리 공유해요). 관리자 탭에서 초대 코드를 복사해 멤버
                탭에서 합류하고, 관리자 탭으로 돌아오면 멤버가 보여요.
              </Box>
              <Button
                variant="link"
                onClick={() => {
                  resetMockWorkspaces()
                  resetMockLinks()
                  toast('테스트 데이터를 처음 상태로 되돌렸어요.', 'success')
                }}
              >
                테스트 데이터 초기화
              </Button>
            </SpaceBetween>
          </Container>
        )}
      </SpaceBetween>
    </ContentLayout>
  )
}
