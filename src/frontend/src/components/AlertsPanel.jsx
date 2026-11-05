// 홈 오른쪽 "확인 필요": 만료 임박·방문자 급증 알림 (#210, 서버 #209). 급한 것부터 서버가 정렬해 준다.
import { useCallback, useEffect, useRef, useState } from 'react'
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

export function AlertsPanel({ ws, reloadKey }) {
  const navigate = useNavigate()
  const [state, setState] = useState('loading') // loading | ready | error
  const [alerts, setAlerts] = useState([])
  const latest = useRef(0)

  const load = useCallback(() => {
    const request = ++latest.current
    setState('loading')
    getAlerts(ws)
      .then((res) => {
        if (request !== latest.current) return
        setAlerts(res)
        setState('ready')
      })
      .catch(() => request === latest.current && setState('error'))
  }, [ws])

  useEffect(() => {
    load()
  }, [load, reloadKey]) // reloadKey: 홈에서 링크를 만들면 다시 불러온다

  return (
    <Container
      header={
        <Header variant="h2" counter={state === 'ready' ? `(${alerts.length})` : undefined}>
          확인 필요
        </Header>
      }
    >
      {state === 'loading' && <StatusIndicator type="loading">불러오는 중</StatusIndicator>}
      {state === 'error' && (
        <Alert
          type="error"
          header="알림을 불러오지 못했어요"
          action={<Button onClick={load}>다시 시도</Button>}
        />
      )}
      {state === 'ready' && alerts.length === 0 && (
        <Box color="text-body-secondary">지금 확인할 링크가 없어요.</Box>
      )}
      {state === 'ready' && alerts.length > 0 && (
        <SpaceBetween size="m">
          {alerts.map((a) => (
            <div key={`${a.type}-${a.link.linkId}`}>
              <StatusIndicator type={a.level === 'ACTION' ? 'warning' : 'info'}>
                <Link
                  href={`/links/${a.link.linkId}/stats`}
                  onFollow={(e) => {
                    e.preventDefault()
                    navigate(`/links/${a.link.linkId}/stats`)
                  }}
                >
                  {a.link.title || a.link.shortUrl}
                </Link>
              </StatusIndicator>
              <Box color="text-body-secondary" fontSize="body-s">
                {alertMessage(a)}
              </Box>
            </div>
          ))}
        </SpaceBetween>
      )}
    </Container>
  )
}
