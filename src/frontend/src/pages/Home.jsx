import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import Alert from '@cloudscape-design/components/alert'
import Box from '@cloudscape-design/components/box'
import Button from '@cloudscape-design/components/button'
import Container from '@cloudscape-design/components/container'
import ContentLayout from '@cloudscape-design/components/content-layout'
import CopyToClipboard from '@cloudscape-design/components/copy-to-clipboard'
import Form from '@cloudscape-design/components/form'
import FormField from '@cloudscape-design/components/form-field'
import Header from '@cloudscape-design/components/header'
import Input from '@cloudscape-design/components/input'
import Link from '@cloudscape-design/components/link'
import SpaceBetween from '@cloudscape-design/components/space-between'
import { useAuth } from '../context/AuthContext'
import { createLink } from '../api/links'
import { ApiError } from '../api/http'
import { ExpiryInput } from '../components/ExpiryInput'
import { toast } from '../components/Toast'

const TITLE_MAX = 100

export function Home() {
  const { user } = useAuth()
  const navigate = useNavigate()

  const [originalUrl, setOriginalUrl] = useState('')
  const [title, setTitle] = useState('')
  const [expiresAt, setExpiresAt] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [result, setResult] = useState(null)
  const [errors, setErrors] = useState({})
  const [serverError, setServerError] = useState('')

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
      const link = await createLink({
        originalUrl: originalUrl.trim(),
        title: title.trim() || undefined,
        expiresAt: new Date(expiresAt).toISOString()
      })
      setResult(link)
      toast('단축 링크가 생성되었습니다.', 'success')
    } catch (err) {
      const message = err instanceof ApiError ? err.message : '알 수 없는 오류가 발생했습니다.'
      setServerError(message)
      toast(message, 'error')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <ContentLayout
      header={
        <Header variant="h1" description="긴 URL과 만료일을 입력하면 단축 링크를 즉시 발급합니다.">
          링크를 짧고, 안전하고, 추적 가능하게
        </Header>
      }
    >
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
              <Button variant="primary" formAction="submit" loading={submitting}>
                단축 링크 만들기
              </Button>
            }
          >
            <Container header={<Header variant="h2">새 링크</Header>}>
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
      </SpaceBetween>
    </ContentLayout>
  )
}

function truncate(str, n) {
  return str.length > n ? str.slice(0, n) + '…' : str
}
