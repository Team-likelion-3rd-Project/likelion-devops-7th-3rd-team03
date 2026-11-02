// 워크스페이스가 정해진 뒤에만 children을 그린다. 불러오는 중, 실패, 소속 팀 없음일 때는 안내를 보여 준다.
// 팀이 바뀌면 children을 새로 시작해서 이전 팀의 목록이나 입력값이 남지 않게 한다.
import { Fragment } from 'react'
import Alert from '@cloudscape-design/components/alert'
import Box from '@cloudscape-design/components/box'
import Button from '@cloudscape-design/components/button'
import StatusIndicator from '@cloudscape-design/components/status-indicator'
import { useWorkspace } from '../context/WorkspaceContext'

export function RequireWorkspace({ children }) {
  const { current, status, refresh } = useWorkspace()

  if (current) return <Fragment key={current.workspaceId}>{children}</Fragment>

  if (status === 'error') {
    return (
      <Alert
        type="error"
        header="워크스페이스를 불러오지 못했어요"
        action={<Button onClick={() => refresh()}>다시 시도</Button>}
      >
        잠시 후 다시 시도해 주세요.
      </Alert>
    )
  }

  if (status === 'ready') {
    return (
      <Alert type="warning" header="소속된 워크스페이스가 없어요">
        상단바에서 새 워크스페이스를 만들거나 초대 코드로 합류해 주세요.
      </Alert>
    )
  }

  return (
    <Box textAlign="center" padding="xxl">
      <StatusIndicator type="loading">불러오는 중</StatusIndicator>
    </Box>
  )
}
