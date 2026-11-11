import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import Box from '@cloudscape-design/components/box'
import Button from '@cloudscape-design/components/button'
import FormField from '@cloudscape-design/components/form-field'
import Header from '@cloudscape-design/components/header'
import Input from '@cloudscape-design/components/input'
import Link from '@cloudscape-design/components/link'
import Modal from '@cloudscape-design/components/modal'
import Pagination from '@cloudscape-design/components/pagination'
import SpaceBetween from '@cloudscape-design/components/space-between'
import StatusIndicator from '@cloudscape-design/components/status-indicator'
import Table from '@cloudscape-design/components/table'
import { listLinks, updateLink, deleteLink } from '../api/links'
import { ApiError } from '../api/http'
import { useWorkspace } from '../context/WorkspaceContext'
import { RequireWorkspace } from '../components/RequireWorkspace'
import { ExpiryInput } from '../components/ExpiryInput'
import { toast } from '../components/Toast'

const PAGE_SIZE = 20
const TITLE_MAX = 100

// 새로고침 직후에는 워크스페이스가 아직 없다. 팀을 바꾸면 쪽·수정 상태까지 처음부터 다시 시작한다.
export function Dashboard() {
  return (
    <RequireWorkspace>
      <LinkList />
    </RequireWorkspace>
  )
}

function LinkList() {
  const { current: ws, refreshLinkCount } = useWorkspace()
  const navigate = useNavigate()
  const [page, setPage] = useState(0)
  const [data, setData] = useState(null)
  const [status, setStatus] = useState('loading') // loading | ready | error
  const [editingLink, setEditingLink] = useState(null)
  const [deletingLink, setDeletingLink] = useState(null)

  const load = useCallback(async () => {
    setStatus('loading')
    try {
      setData(await listLinks(ws, page, PAGE_SIZE))
      setStatus('ready')
    } catch (err) {
      setStatus('error')
      toast(err instanceof ApiError ? err.message : '링크 목록을 불러오지 못했습니다.', 'error')
    }
  }, [ws, page])

  useEffect(() => {
    load()
  }, [load])

  async function handleDelete(link) {
    setDeletingLink(null)
    try {
      await deleteLink(ws, link.linkId)
      toast('링크를 삭제했습니다.', 'success')
      refreshLinkCount()
      // 마지막 쪽의 마지막 링크를 지우면 그 쪽이 비므로 앞 쪽으로 간다
      if (data.links.length === 1 && page > 0) setPage(page - 1)
      else load()
    } catch (err) {
      toast(err instanceof ApiError ? err.message : '삭제에 실패했습니다.', 'error')
    }
  }

  async function handleSaveEdit(link, form) {
    try {
      await updateLink(ws, link.linkId, {
        originalUrl: form.originalUrl.trim() || undefined,
        title: form.title.trim() || undefined,
        expiresAt: form.expiresAt ? new Date(form.expiresAt).toISOString() : undefined
      })
      toast('링크를 수정했습니다.', 'success')
      setEditingLink(null)
      load()
    } catch (err) {
      toast(err instanceof ApiError ? err.message : '수정에 실패했습니다.', 'error')
    }
  }

  const links = data?.links ?? []
  const totalPages = data?.pagination.totalPages ?? 0

  return (
    <>
      <Table
        variant="full-page"
        items={links}
        trackBy="linkId"
        loading={status === 'loading'}
        loadingText="링크를 불러오는 중"
        header={
          <Header
            variant="awsui-h1-sticky"
            counter={data ? `(${data.pagination.totalElements})` : undefined}
            description={
              ws.personal ? undefined : `${ws.name}의 링크예요. 팀원이 만든 링크도 함께 보여요.`
            }
            actions={
              <Button variant="primary" onClick={() => navigate('/')}>
                새 링크
              </Button>
            }
          >
            링크
          </Header>
        }
        pagination={
          totalPages > 1 && (
            <Pagination
              currentPageIndex={page + 1}
              pagesCount={totalPages}
              onChange={({ detail }) => setPage(detail.currentPageIndex - 1)}
            />
          )
        }
        empty={
          status === 'error' ? (
            <Box textAlign="center" color="inherit">
              <SpaceBetween size="m">
                <b>링크 목록을 불러오지 못했습니다.</b>
                <Button onClick={load}>다시 시도</Button>
              </SpaceBetween>
            </Box>
          ) : (
            <Box textAlign="center" color="inherit">
              <SpaceBetween size="m">
                <b>아직 만든 링크가 없습니다.</b>
                <Button onClick={() => navigate('/')}>지금 첫 링크를 만들어보세요</Button>
              </SpaceBetween>
            </Box>
          )
        }
        columnDefinitions={[
          {
            id: 'shortUrl',
            header: '단축 URL',
            isRowHeader: true,
            cell: (link) => (
              <Link href={link.shortUrl} external>
                {link.shortUrl}
              </Link>
            )
          },
          {
            id: 'originalUrl',
            header: '원본 URL',
            cell: (link) => <span title={link.originalUrl}>{truncate(link.originalUrl, 48)}</span>
          },
          { id: 'title', header: '제목', cell: (link) => link.title || '-' },
          { id: 'creator', header: '만든 사람', cell: (link) => link.creator || '-' },
          {
            id: 'status',
            header: '상태',
            cell: (link) =>
              isExpired(link.expiresAt) ? (
                <StatusIndicator type="stopped">만료</StatusIndicator>
              ) : (
                <StatusIndicator type="success">활성</StatusIndicator>
              )
          },
          { id: 'expiresAt', header: '만료일', cell: (link) => formatDate(link.expiresAt) },
          { id: 'createdAt', header: '생성일', cell: (link) => formatDate(link.createdAt) },
          {
            id: 'actions',
            header: '',
            minWidth: 150, // 좁은 화면에서 통계·수정·삭제가 글자 단위로 줄바꿈되지 않게
            cell: (link) => (
              <SpaceBetween direction="horizontal" size="xs">
                <Button
                  variant="inline-link"
                  onClick={() => navigate(`/links/${link.linkId}/stats`)}
                >
                  통계
                </Button>
                <Button variant="inline-link" onClick={() => setEditingLink(link)}>
                  수정
                </Button>
                <Button variant="inline-link" onClick={() => setDeletingLink(link)}>
                  삭제
                </Button>
              </SpaceBetween>
            )
          }
        ]}
      />

      {editingLink && (
        <EditLinkModal
          link={editingLink}
          onClose={() => setEditingLink(null)}
          onSave={(form) => handleSaveEdit(editingLink, form)}
        />
      )}
      {deletingLink && (
        <Modal
          visible
          onDismiss={() => setDeletingLink(null)}
          header="링크를 삭제할까요?"
          footer={
            <Box float="right">
              <SpaceBetween direction="horizontal" size="xs">
                <Button variant="link" onClick={() => setDeletingLink(null)}>
                  취소
                </Button>
                <Button variant="primary" onClick={() => handleDelete(deletingLink)}>
                  삭제
                </Button>
              </SpaceBetween>
            </Box>
          }
        >
          “{deletingLink.title || deletingLink.shortUrl}” 링크를 삭제합니다.
        </Modal>
      )}
    </>
  )
}

function EditLinkModal({ link, onClose, onSave }) {
  const [originalUrl, setOriginalUrl] = useState(link.originalUrl)
  const [title, setTitle] = useState(link.title || '')
  const [expiresAt, setExpiresAt] = useState(toLocalInput(link.expiresAt))

  return (
    <Modal
      visible
      onDismiss={onClose}
      header="링크 수정"
      footer={
        <Box float="right">
          <SpaceBetween direction="horizontal" size="xs">
            <Button variant="link" onClick={onClose}>
              취소
            </Button>
            <Button variant="primary" onClick={() => onSave({ originalUrl, title, expiresAt })}>
              저장
            </Button>
          </SpaceBetween>
        </Box>
      }
    >
      <SpaceBetween size="l">
        <FormField label="원본 URL">
          <Input value={originalUrl} onChange={({ detail }) => setOriginalUrl(detail.value)} />
        </FormField>
        <FormField label="제목" constraintText={`${TITLE_MAX}자 이내`}>
          <Input
            value={title}
            onChange={({ detail }) => setTitle(detail.value.slice(0, TITLE_MAX))}
          />
        </FormField>
        <FormField label="만료일">
          <ExpiryInput value={expiresAt} onChange={setExpiresAt} />
        </FormField>
      </SpaceBetween>
    </Modal>
  )
}

// 백엔드 Link.isExpired()와 동일한 경계값(now >= expiresAt이면 만료)을 사용한다.
function isExpired(expiresAt) {
  return Boolean(expiresAt) && Date.now() >= new Date(expiresAt).getTime()
}

function truncate(str, n) {
  return str.length > n ? str.slice(0, n) + '…' : str
}

function formatDate(iso) {
  if (!iso) return '-'
  return new Date(iso).toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  })
}

// ISO(UTC) 시각을 만료일 입력이 쓰는 현지 시각 'YYYY-MM-DDTHH:mm'으로 바꾼다
function toLocalInput(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}
