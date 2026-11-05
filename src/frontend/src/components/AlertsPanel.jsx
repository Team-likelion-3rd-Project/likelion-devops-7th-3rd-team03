// 홈 오른쪽 "확인 필요": 만료 임박·방문자 급증 알림 (#210, 서버 #209). 서버가 급한 것부터 정렬해 준다.
// 급한 정도(level)로 묶어서 보여 준다: ACTION은 "지금 확인", WATCH는 "지켜보기".
import { useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import Alert from '@cloudscape-design/components/alert'
import Box from '@cloudscape-design/components/box'
import Button from '@cloudscape-design/components/button'
import Container from '@cloudscape-design/components/container'
import Header from '@cloudscape-design/components/header'
import Link from '@cloudscape-design/components/link'
import SpaceBetween from '@cloudscape-design/components/space-between'
import StatusIndicator from '@cloudscape-design/components/status-indicator'
import { alertMessage, getAlerts } from '../api/alerts'
import { useLoad } from '../hooks/useLoad'

const GROUPS = [
  { level: 'ACTION', label: '지금 확인', indicator: 'error' },
  { level: 'WATCH', label: '지켜보기', indicator: 'warning' }
]

export function AlertsPanel({ ws, reloadKey }) {
  const navigate = useNavigate()
  const load = useCallback(() => getAlerts(ws), [ws])
  const { state, data, reload } = useLoad(load, reloadKey)
  const alerts = state === 'error' ? null : data

  return (
    <Container
      header={
        <Header variant="h2" counter={alerts ? `(${alerts.length})` : undefined}>
          확인 필요
        </Header>
      }
    >
      {state === 'error' && (
        <Alert
          type="error"
          header="알림을 불러오지 못했어요"
          action={<Button onClick={reload}>다시 시도</Button>}
        />
      )}
      {!alerts && state === 'loading' && (
        <StatusIndicator type="loading">불러오는 중</StatusIndicator>
      )}
      {alerts && alerts.length === 0 && (
        <Box color="text-body-secondary">확인할 링크가 없어요.</Box>
      )}
      {alerts && alerts.length > 0 && (
        <SpaceBetween size="l">
          {GROUPS.map((group) => {
            // ACTION이 아니면 모두 지켜보기로 본다(서버가 모르는 level을 줘도 알림이 사라지지 않게)
            const items = alerts.filter(
              (a) => (a.level === 'ACTION') === (group.level === 'ACTION')
            )
            if (items.length === 0) return null
            return (
              <SpaceBetween size="s" key={group.level}>
                <Box fontWeight="bold">
                  <StatusIndicator type={group.indicator}>{group.label}</StatusIndicator>
                </Box>
                {items.map((a) => (
                  <div key={`${a.type}-${a.link.linkId}`}>
                    <Link
                      href={`/links/${a.link.linkId}/stats`}
                      onFollow={(e) => {
                        e.preventDefault()
                        navigate(`/links/${a.link.linkId}/stats`)
                      }}
                    >
                      {a.link.title || a.link.shortUrl}
                    </Link>
                    <Box color="text-body-secondary" fontSize="body-s">
                      {alertMessage(a)}
                    </Box>
                  </div>
                ))}
              </SpaceBetween>
            )
          })}
        </SpaceBetween>
      )}
    </Container>
  )
}
