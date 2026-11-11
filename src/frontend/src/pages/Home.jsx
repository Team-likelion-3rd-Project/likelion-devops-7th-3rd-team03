import { lazy, Suspense, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import Alert from '@cloudscape-design/components/alert'
import Box from '@cloudscape-design/components/box'
import Button from '@cloudscape-design/components/button'
import Container from '@cloudscape-design/components/container'
import ContentLayout from '@cloudscape-design/components/content-layout'
import CopyToClipboard from '@cloudscape-design/components/copy-to-clipboard'
import Form from '@cloudscape-design/components/form'
import Grid from '@cloudscape-design/components/grid'
import FormField from '@cloudscape-design/components/form-field'
import Header from '@cloudscape-design/components/header'
import Input from '@cloudscape-design/components/input'
import Link from '@cloudscape-design/components/link'
import Spinner from '@cloudscape-design/components/spinner'
import SpaceBetween from '@cloudscape-design/components/space-between'
import { useAuth } from '../context/AuthContext'
import { useWorkspace } from '../context/WorkspaceContext'
import { createLink } from '../api/links'
import { MOCK_TEAMS } from '../api/workspaces'
import { ApiError } from '../api/http'
import { ExpiryInput } from '../components/ExpiryInput'
import { toast } from '../components/Toast'
import { AlertsPanel } from '../components/AlertsPanel'

// 운영 현황(차트)은 로그인한 사용자만 보므로, 처음 화면(비로그인 홈)에서는 내려받지 않는다.
const TeamOverview = lazy(() =>
  import('../components/TeamOverview').then((m) => ({ default: m.TeamOverview }))
)

const TITLE_MAX = 100

export function Home() {
  const { user } = useAuth()
  const { current, refreshLinkCount } = useWorkspace()
  const navigate = useNavigate()

  const [originalUrl, setOriginalUrl] = useState('')
  const [title, setTitle] = useState('')
  const [expiresAt, setExpiresAt] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [result, setResult] = useState(null)
  const [errors, setErrors] = useState({})
  const [serverError, setServerError] = useState('')
  const [created, setCreated] = useState(0) // 링크를 만들 때마다 올라가 운영 현황·알림을 다시 불러오게 한다

  async function handleSubmit() {
    setServerError('')

    if (!user) {
      toast('링크를 만들려면 먼저 로그인하세요.', 'info')
      navigate('/login')
      return
    }

    const found = {}
    if (!originalUrl.trim()) found.originalUrl = '원본 URL을 입력해 주세요.'
    if (!expiresAt) found.expiresAt = '만료일은 필수입니다.'
    setErrors(found)
    if (Object.keys(found).length > 0) return

    setSubmitting(true)
    try {
      const link = await createLink(current, {
        originalUrl: originalUrl.trim(),
        title: title.trim() || undefined,
        expiresAt: new Date(expiresAt).toISOString()
      })
      setResult(link)
      refreshLinkCount()
      setCreated((n) => n + 1)
      toast('단축 링크가 생성되었습니다.', 'success')
    } catch (err) {
      const message = err instanceof ApiError ? err.message : '알 수 없는 오류가 발생했습니다.'
      setServerError(message)
      toast(message, 'error')
    } finally {
      setSubmitting(false)
    }
  }

  // 로그인해서 워크스페이스가 정해졌으면 운영 현황(왼쪽)과 할 일(오른쪽)을 같이 보여 준다.
  // 팀 활동 요약은 이 화면에 넣지 않는다. 팀 활동 이슈(미등록)가 끝난 뒤 따로 붙인다.
  // 개발 서버 기본 모드(mock 워크스페이스)에는 통계·알림 API가 없어서 영역마다 안내만 보인다. VITE_WORKSPACE_API=real 이면 현황이 보인다.
  const showOverview = Boolean(user && current)

  const main = (
    <SpaceBetween size="l">
      <form
        onSubmit={(e) => {
          e.preventDefault()
          handleSubmit()
        }}
      >
        <Form
          errorText={serverError}
          actions={
            <Button
              variant="primary"
              formAction="submit"
              loading={submitting}
              disabled={Boolean(user) && !current}
            >
              단축 링크 만들기
            </Button>
          }
        >
          <Container
            header={
              <Header
                variant="h2"
                description={
                  current && !current.personal
                    ? `${current.name}에 만들어요. 팀원 모두 이 링크를 볼 수 있어요.`
                    : undefined
                }
              >
                새 링크
              </Header>
            }
          >
            <SpaceBetween size="l">
              <FormField label="원본 URL" errorText={errors.originalUrl}>
                <Input
                  value={originalUrl}
                  onChange={({ detail }) => setOriginalUrl(detail.value)}
                  placeholder="https://example.com/very/long/path?query=1"
                  inputMode="url"
                />
              </FormField>
              <FormField label="제목" constraintText={`선택 · ${TITLE_MAX}자 이내`}>
                <Input
                  value={title}
                  onChange={({ detail }) => setTitle(detail.value.slice(0, TITLE_MAX))}
                  placeholder="런칭 캠페인 링크"
                />
              </FormField>
              <FormField label="만료일" constraintText="필수" errorText={errors.expiresAt}>
                <ExpiryInput value={expiresAt} onChange={setExpiresAt} />
              </FormField>
              {user && !current && (
                <Box color="text-body-secondary" fontSize="body-s">
                  워크스페이스를 불러온 뒤에 만들 수 있어요. 계속 안 되면 상단바의 “팀 목록 다시
                  불러오기”를 눌러 주세요.
                </Box>
              )}
              {!user && (
                <Box color="text-body-secondary" fontSize="body-s">
                  로그인하지 않아도 입력은 가능하지만, 발급 시 로그인 화면으로 이동합니다.
                </Box>
              )}
            </SpaceBetween>
          </Container>
        </Form>
      </form>

      {result && (
        <Alert type="success" header="단축 링크를 만들었어요">
          <SpaceBetween size="xs">
            <CopyToClipboard
              variant="inline"
              textToCopy={result.shortUrl}
              copyButtonAriaLabel="단축 URL 복사"
              copySuccessText="복사했어요"
              copyErrorText="복사하지 못했어요"
            />
            <Link href={result.shortUrl} external>
              {result.shortUrl}
            </Link>
            <Box color="text-body-secondary" fontSize="body-s">
              원본: {truncate(result.originalUrl, 60)}
            </Box>
            <Link
              href="/dashboard"
              onFollow={(e) => {
                e.preventDefault()
                navigate('/dashboard')
              }}
            >
              내 링크에서 확인하기
            </Link>
          </SpaceBetween>
        </Alert>
      )}
      {showOverview &&
        (MOCK_TEAMS ? (
          <MockNote title="확인 필요" />
        ) : (
          <AlertsPanel key={current.workspaceId} ws={current} reloadKey={created} />
        ))}
    </SpaceBetween>
  )

  return (
    <ContentLayout
      header={
        showOverview ? (
          <Header
            variant="h1"
            description={
              current.personal ? '내 링크의 운영 현황이에요.' : `${current.name}의 운영 현황이에요.`
            }
          >
            홈
          </Header>
        ) : (
          <Header
            variant="h1"
            description="긴 URL과 만료일을 입력하면 단축 링크를 즉시 발급합니다."
          >
            링크를 짧고, 안전하고, 추적 가능하게
          </Header>
        )
      }
    >
      {showOverview ? (
        <Grid
          gridDefinition={[{ colspan: { default: 12, m: 8 } }, { colspan: { default: 12, m: 4 } }]}
        >
          {MOCK_TEAMS ? (
            <MockNote title="운영 현황" />
          ) : (
            <Suspense fallback={<Spinner size="large" />}>
              <TeamOverview key={current.workspaceId} ws={current} reloadKey={created} />
            </Suspense>
          )}
          {main}
        </Grid>
      ) : (
        main
      )}
    </ContentLayout>
  )
}

function truncate(str, n) {
  return str.length > n ? str.slice(0, n) + '…' : str
}

// 개발 서버 기본 모드(mock 워크스페이스)에는 통계·알림 API가 없다.
function MockNote({ title }) {
  return (
    <Container header={<Header variant="h2">{title}</Header>}>
      <Box color="text-body-secondary">
        개발 mock 모드에는 통계가 없어요. VITE_WORKSPACE_API=real 로 실행하면 보여요.
      </Box>
    </Container>
  )
}
