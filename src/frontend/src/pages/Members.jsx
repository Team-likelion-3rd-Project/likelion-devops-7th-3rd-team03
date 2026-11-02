// 시안 pages/Members.jsx를 따른 멤버 화면. 관리자만 역할 변경·내보내기·초대 코드를 쓴다.
import { useCallback, useEffect, useState } from 'react'
import Alert from '@cloudscape-design/components/alert'
import Badge from '@cloudscape-design/components/badge'
import Box from '@cloudscape-design/components/box'
import Button from '@cloudscape-design/components/button'
import CopyToClipboard from '@cloudscape-design/components/copy-to-clipboard'
import FormField from '@cloudscape-design/components/form-field'
import Header from '@cloudscape-design/components/header'
import Input from '@cloudscape-design/components/input'
import Modal from '@cloudscape-design/components/modal'
import Select from '@cloudscape-design/components/select'
import SpaceBetween from '@cloudscape-design/components/space-between'
import Table from '@cloudscape-design/components/table'
import { useAuth } from '../context/AuthContext'
import { useWorkspace } from '../context/WorkspaceContext'
import {
  changeMemberRole,
  getInviteCode,
  listMembers,
  reissueInviteCode,
  removeMember
} from '../api/workspaces'
import { ROLES, ROLE_DESCRIPTION, roleLabel } from '../permissions'
import { RequireWorkspace } from '../components/RequireWorkspace'
import { toast } from '../components/Toast'

// 모르는 역할은 맨 뒤로 보낸다
const roleRank = (role) => (ROLES.includes(role) ? ROLES.indexOf(role) : ROLES.length)

const roleOption = (role) => ({
  value: role,
  label: roleLabel(role),
  description: ROLE_DESCRIPTION[role]
})

const fmtDate = (iso) =>
  new Date(iso).toLocaleDateString('ko-KR', { year: 'numeric', month: 'long', day: 'numeric' })

// 팀을 바꾸면 RequireWorkspace가 이 화면을 새로 시작해서 이전 팀의 멤버가 남아 보이지 않는다
export function Members() {
  return (
    <RequireWorkspace>
      <MemberList />
    </RequireWorkspace>
  )
}

function MemberList() {
  const { user } = useAuth()
  const { current, can } = useWorkspace()
  const id = current?.workspaceId
  const canManage = can('member:manage')
  const [members, setMembers] = useState([])
  const [status, setStatus] = useState('loading') // loading | ready | error
  const [inviting, setInviting] = useState(false)
  const [removing, setRemoving] = useState(null)

  const load = useCallback(async () => {
    try {
      const list = await listMembers(id)
      // 관리자가 위, 같은 역할 안에서는 먼저 합류한 사람이 위
      setMembers(
        [...list].sort(
          (a, b) =>
            roleRank(a.role) - roleRank(b.role) || new Date(a.joinedAt) - new Date(b.joinedAt)
        )
      )
      setStatus('ready')
    } catch {
      setStatus('error')
    }
  }, [id])

  useEffect(() => {
    if (id) load()
  }, [id, load])

  // 다른 탭에서 멤버가 바뀌었을 수 있으니 이 창으로 돌아오면 다시 불러온다
  useEffect(() => {
    if (!id) return
    window.addEventListener('focus', load)
    return () => window.removeEventListener('focus', load)
  }, [id, load])

  const adminCount = members.filter((m) => m.role === 'ADMIN').length
  // 팀에는 관리자가 최소 한 명 남아야 한다
  const editable = (row) =>
    canManage && row.userId !== user.userId && !(row.role === 'ADMIN' && adminCount === 1)

  const act = async (fn, message) => {
    try {
      await fn()
      toast(message, 'success')
      await load()
    } catch (err) {
      toast(err.message, 'error')
    }
  }

  return (
    <>
      <Table
        variant="full-page"
        items={members}
        trackBy="userId"
        loading={status === 'loading'}
        loadingText="멤버를 불러오는 중"
        header={
          <Header
            variant="awsui-h1-sticky"
            counter={status === 'ready' ? `(${members.length})` : undefined}
            description={
              !canManage ? '초대 코드와 역할 변경은 관리자만 다룰 수 있어요.' : undefined
            }
            actions={
              canManage && (
                <Button variant="primary" iconName="key" onClick={() => setInviting(true)}>
                  초대 코드
                </Button>
              )
            }
          >
            멤버
          </Header>
        }
        empty={
          status === 'error' ? (
            <Box textAlign="center" color="inherit">
              <SpaceBetween size="m">
                <b>멤버를 불러오지 못했어요.</b>
                <Button onClick={load}>다시 시도</Button>
              </SpaceBetween>
            </Box>
          ) : (
            <Box textAlign="center" color="text-body-secondary">
              멤버가 없어요.
            </Box>
          )
        }
        columnDefinitions={[
          {
            id: 'name',
            header: '이름',
            isRowHeader: true,
            cell: (row) => (
              <SpaceBetween direction="horizontal" size="xs" alignItems="center">
                <span>{row.name}</span>
                {row.userId === user.userId && <Badge color="blue">나</Badge>}
              </SpaceBetween>
            ),
            width: 180
          },
          { id: 'email', header: '이메일', cell: (row) => row.email, width: 240 },
          {
            id: 'role',
            header: '역할',
            cell: (row) =>
              editable(row) ? (
                <Select
                  selectedOption={roleOption(row.role)}
                  options={ROLES.map(roleOption)}
                  onChange={({ detail }) =>
                    act(
                      () => changeMemberRole(id, row.userId, detail.selectedOption.value),
                      `${row.name}님의 역할을 ${detail.selectedOption.label}로 바꿨어요.`
                    )
                  }
                  expandToViewport
                  ariaLabel={`${row.name} 역할`}
                />
              ) : (
                roleLabel(row.role)
              ),
            width: 200
          },
          { id: 'joined', header: '참여일', cell: (row) => fmtDate(row.joinedAt), width: 150 },
          {
            id: 'actions',
            header: '',
            cell: (row) =>
              editable(row) && (
                <Button variant="inline-link" onClick={() => setRemoving(row)}>
                  내보내기
                </Button>
              ),
            width: 110
          }
        ]}
      />

      {inviting && <InviteCodeModal workspace={current} onDismiss={() => setInviting(false)} />}
      {removing && (
        <Modal
          visible
          onDismiss={() => setRemoving(null)}
          header={`${removing.name}님을 내보낼까요?`}
          footer={
            <Box float="right">
              <SpaceBetween direction="horizontal" size="xs">
                <Button variant="link" onClick={() => setRemoving(null)}>
                  취소
                </Button>
                <Button
                  variant="primary"
                  onClick={() => {
                    const target = removing
                    setRemoving(null)
                    act(() => removeMember(id, target.userId), `${target.name}님을 내보냈어요.`)
                  }}
                >
                  내보내기
                </Button>
              </SpaceBetween>
            </Box>
          }
        >
          {removing.name}님이 만든 링크는 워크스페이스에 그대로 남아요. 다시 함께하려면 초대 코드로
          합류하면 돼요.
        </Modal>
      )}
    </>
  )
}

// 팀당 코드 하나. 처음 열 때 서버가 발급하고, 유출이 의심되면 재발급한다.
function InviteCodeModal({ workspace, onDismiss }) {
  const [code, setCode] = useState(null)
  const [error, setError] = useState(null)
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    getInviteCode(workspace.workspaceId)
      .then(setCode)
      .catch((err) => setError(err.message))
  }, [workspace.workspaceId])

  async function reissue() {
    setBusy(true)
    try {
      setCode(await reissueInviteCode(workspace.workspaceId))
      setConfirming(false)
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      visible
      onDismiss={onDismiss}
      header="초대 코드"
      size="medium"
      footer={
        <Box float="right">
          <SpaceBetween direction="horizontal" size="xs">
            {confirming ? (
              <>
                <Button variant="link" onClick={() => setConfirming(false)}>
                  취소
                </Button>
                <Button variant="primary" loading={busy} onClick={reissue}>
                  재발급
                </Button>
              </>
            ) : (
              <>
                <Button disabled={!code} onClick={() => setConfirming(true)}>
                  재발급
                </Button>
                <Button variant="primary" onClick={onDismiss}>
                  닫기
                </Button>
              </>
            )}
          </SpaceBetween>
        </Box>
      }
    >
      <SpaceBetween size="m">
        <Box>
          Snipy에 가입한 사람이 <strong>워크스페이스 전환 → 초대 코드로 합류</strong>에서 이 코드를
          입력하면 <strong>{workspace.name}</strong>에 <strong>{roleLabel('MEMBER')}</strong>로
          합류해요. 역할은 합류 후에 바꿀 수 있어요.
        </Box>
        {error && <Alert type="error">{error}</Alert>}
        <FormField label="코드">
          <SpaceBetween direction="horizontal" size="xs" alignItems="center">
            <div className="invite-code">
              <Input
                value={code ?? ''}
                readOnly
                ariaLabel="초대 코드"
                placeholder={code ? '' : '불러오는 중…'}
              />
            </div>
            <CopyToClipboard
              variant="button"
              textToCopy={code ?? ''}
              copyButtonText="복사"
              copySuccessText="복사했어요"
              copyErrorText="복사하지 못했어요"
            />
          </SpaceBetween>
        </FormField>
        {confirming && (
          <Alert type="warning">
            재발급하면 지금 코드는 바로 쓸 수 없게 돼요. 이미 합류한 멤버는 그대로예요.
          </Alert>
        )}
      </SpaceBetween>
    </Modal>
  )
}
