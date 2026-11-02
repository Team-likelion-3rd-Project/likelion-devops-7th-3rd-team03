// 시안 pages/Settings.jsx를 따른 설정 화면. 이름 변경과 삭제는 관리자만 할 수 있다.
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import Alert from '@cloudscape-design/components/alert'
import Box from '@cloudscape-design/components/box'
import Button from '@cloudscape-design/components/button'
import Container from '@cloudscape-design/components/container'
import ContentLayout from '@cloudscape-design/components/content-layout'
import FormField from '@cloudscape-design/components/form-field'
import Header from '@cloudscape-design/components/header'
import Input from '@cloudscape-design/components/input'
import KeyValuePairs from '@cloudscape-design/components/key-value-pairs'
import Modal from '@cloudscape-design/components/modal'
import SpaceBetween from '@cloudscape-design/components/space-between'
import { useWorkspace } from '../context/WorkspaceContext'
import { deleteWorkspace, renameWorkspace } from '../api/workspaces'
import { toast } from '../components/Toast'

const NAME_MAX = 50 // workspaces.name VARCHAR(50)

export function Settings() {
  const { current, can, refresh } = useWorkspace()
  const editable = can('workspace:update')
  const [name, setName] = useState(current.name)
  const [saving, setSaving] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const nameError = !name.trim()
    ? '이름을 입력해 주세요.'
    : name.trim().length > NAME_MAX
      ? `${NAME_MAX}자 이내로 입력해 주세요.`
      : null

  async function save() {
    setSaving(true)
    try {
      await renameWorkspace(current.workspaceId, name.trim())
      await refresh(current.workspaceId)
      toast('이름을 바꿨어요.', 'success')
    } catch (err) {
      toast(err.message, 'error')
    } finally {
      setSaving(false)
    }
  }

  return (
    <ContentLayout header={<Header variant="h1">설정</Header>}>
      <SpaceBetween size="l">
        {!editable && <Alert type="info">설정은 관리자만 바꿀 수 있어요.</Alert>}

        <Container
          header={<Header variant="h2">기본 정보</Header>}
          footer={
            editable && (
              <Box float="right">
                <Button
                  variant="primary"
                  loading={saving}
                  disabled={Boolean(nameError) || name.trim() === current.name}
                  onClick={save}
                >
                  저장
                </Button>
              </Box>
            )
          }
        >
          <SpaceBetween size="l">
            <FormField
              label="이름"
              errorText={editable && nameError}
              constraintText="팀원이 알아보기 쉬운 이름으로 정해 주세요."
            >
              <Input
                value={name}
                onChange={({ detail }) => setName(detail.value)}
                disabled={!editable}
              />
            </FormField>
            <KeyValuePairs
              columns={1}
              items={[{ label: '종류', value: current.personal ? '개인' : '팀' }]}
            />
          </SpaceBetween>
        </Container>

        <Container
          header={
            <Header
              variant="h2"
              description="삭제하면 되돌릴 수 없어요. 다른 멤버가 남아 있으면 먼저 내보내야 해요."
            >
              워크스페이스 삭제
            </Header>
          }
        >
          {current.personal ? (
            <Box color="text-body-secondary">개인 워크스페이스는 삭제할 수 없어요.</Box>
          ) : (
            <Button disabled={!can('workspace:delete')} onClick={() => setDeleting(true)}>
              워크스페이스 삭제
            </Button>
          )}
        </Container>
      </SpaceBetween>

      {deleting && <DeleteModal workspace={current} onDismiss={() => setDeleting(false)} />}
    </ContentLayout>
  )
}

function DeleteModal({ workspace, onDismiss }) {
  const { refresh } = useWorkspace()
  const navigate = useNavigate()
  const [typed, setTyped] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  async function confirm() {
    setBusy(true)
    try {
      await deleteWorkspace(workspace.workspaceId)
      toast(`'${workspace.name}' 워크스페이스를 삭제했어요.`, 'success')
      navigate('/', { replace: true })
      await refresh()
    } catch (err) {
      setError(err.message)
      setBusy(false)
    }
  }

  return (
    <Modal
      visible
      onDismiss={onDismiss}
      header={`'${workspace.name}'을 삭제할까요?`}
      footer={
        <Box float="right">
          <SpaceBetween direction="horizontal" size="xs">
            <Button variant="link" onClick={onDismiss}>
              취소
            </Button>
            <Button
              variant="primary"
              loading={busy}
              disabled={typed !== workspace.name}
              onClick={confirm}
            >
              삭제
            </Button>
          </SpaceBetween>
        </Box>
      }
    >
      <SpaceBetween size="m">
        <Alert type="warning">이 워크스페이스의 링크가 모두 비활성화돼요.</Alert>
        {error && <Alert type="error">{error}</Alert>}
        <FormField label={`확인을 위해 '${workspace.name}'을 입력해 주세요.`}>
          <Input
            value={typed}
            onChange={({ detail }) => setTyped(detail.value)}
            placeholder={workspace.name}
          />
        </FormField>
      </SpaceBetween>
    </Modal>
  )
}
