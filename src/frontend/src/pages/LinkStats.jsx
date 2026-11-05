import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import Alert from '@cloudscape-design/components/alert'
import Box from '@cloudscape-design/components/box'
import Button from '@cloudscape-design/components/button'
import Container from '@cloudscape-design/components/container'
import ContentLayout from '@cloudscape-design/components/content-layout'
import Header from '@cloudscape-design/components/header'
import SegmentedControl from '@cloudscape-design/components/segmented-control'
import SpaceBetween from '@cloudscape-design/components/space-between'
import StatusIndicator from '@cloudscape-design/components/status-indicator'
import { listLinks } from '../api/links'
import {
  getDailyStats,
  getReferrerStats,
  getDailyChange,
  getBreakdown,
  getRealtimeCount
} from '../api/stats'
import { ApiError } from '../api/http'
import { useWorkspace } from '../context/WorkspaceContext'
import { RequireWorkspace } from '../components/RequireWorkspace'
import { BreakdownTable, StatGrid, TrendChart } from '../components/StatsParts'
import { countryName } from '../statsLabels'

const RANGE_OPTIONS = [
  { id: '7', text: '7일' },
  { id: '14', text: '14일' },
  { id: '30', text: '30일' }
]

function daysAgoIso(n) {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return d.toISOString().slice(0, 10)
}

// 팀을 바꾸면 RequireWorkspace가 이 화면을 새로 시작하고, 새 팀의 링크 안에서 다시 찾는다.
export function LinkStats() {
  return (
    <RequireWorkspace>
      <LinkStatsView />
    </RequireWorkspace>
  )
}

function LinkStatsView() {
  const { id: linkId } = useParams()
  const navigate = useNavigate()
  const { current } = useWorkspace()

  const [link, setLink] = useState(null)
  const [state, setState] = useState('loading') // loading | ready | notfound | error
  const [days, setDays] = useState(14)

  const [daily, setDaily] = useState(null)
  const [referrers, setReferrers] = useState(null)
  const [change, setChange] = useState(null)
  const [deviceBreakdown, setDeviceBreakdown] = useState(null)
  const [countryBreakdown, setCountryBreakdown] = useState(null)
  const [realtime, setRealtime] = useState(null)
  const [realtimeVisitors, setRealtimeVisitors] = useState(null)
  const [refreshing, setRefreshing] = useState(false)

  // 링크 단건 조회 API가 없어 목록에서 찾는다. 팀당 링크는 100개로 제한되므로(#204) 100개를 불러오면 그 팀의 링크가 모두 들어온다.
  // 지금 선택한 워크스페이스의 링크 안에서만 찾는다. 다른 팀 링크는 여기에 없으므로 안내 화면이 뜬다.
  const loadLink = useCallback(() => {
    if (!current) return
    listLinks(current, 0, 100)
      .then((res) => {
        const found = res.links.find((l) => l.linkId === linkId)
        if (!found) setState('forbidden')
        else setLink(found)
      })
      .catch((err) => setState(err?.status === 403 ? 'forbidden' : 'error'))
  }, [linkId, current])

  useEffect(() => {
    loadLink()
  }, [loadLink])

  const from = daysAgoIso(days - 1)
  const to = daysAgoIso(0)

  const loadStats = useCallback(async () => {
    try {
      const [dailyRes, referrerRes, changeRes, deviceRes, countryRes] = await Promise.all([
        getDailyStats(linkId, from, to),
        getReferrerStats(linkId, from, to),
        getDailyChange(linkId),
        getBreakdown(linkId, 'DEVICE', from, to),
        // REGION은 이제 시·도 코드이고 온프렘에는 값이 없다(항상 UNKNOWN). 국가 단위 분포는 COUNTRY다(#208).
        getBreakdown(linkId, 'COUNTRY', from, to)
      ])
      setDaily(dailyRes)
      setReferrers(referrerRes)
      setChange(changeRes)
      setDeviceBreakdown(deviceRes)
      setCountryBreakdown(countryRes)
      setState('ready')
    } catch (err) {
      // 링크 조회에서 이미 "이 팀의 링크가 아님"으로 판단했다면 통계 오류가 그 안내를 덮어쓰지 않게 한다
      setState((prev) =>
        prev === 'forbidden' || err?.status === 403
          ? 'forbidden'
          : err instanceof ApiError
            ? 'notfound'
            : 'error'
      )
    }
  }, [linkId, from, to])

  useEffect(() => {
    loadStats()
  }, [loadStats])

  async function handleRefresh() {
    setRefreshing(true)
    try {
      await loadStats()
    } finally {
      setRefreshing(false)
    }
  }

  function retry() {
    setState('loading')
    loadLink()
    loadStats()
  }

  useEffect(() => {
    let cancelled = false
    function poll() {
      getRealtimeCount(linkId)
        .then((res) => {
          if (!cancelled) {
            setRealtime(res.realtimeClickCount)
            setRealtimeVisitors(res.realtimeUniqueVisitorCount)
          }
        })
        .catch(() => {})
    }
    poll()
    const timer = setInterval(poll, 10000)
    return () => {
      cancelled = true
      clearInterval(timer)
    }
  }, [linkId])

  if (state === 'forbidden') {
    return (
      <ContentLayout header={<Header variant="h1">볼 수 없는 링크예요</Header>}>
        <Alert
          type="warning"
          action={<Button onClick={() => navigate('/dashboard')}>링크 목록으로</Button>}
        >
          이 링크는 {current?.name ?? '지금 워크스페이스'}에 없어요. 다른 팀의 링크이거나 삭제된
          링크일 수 있어요. 워크스페이스를 바꾸거나 목록에서 다시 골라 주세요.
        </Alert>
      </ContentLayout>
    )
  }

  if (state === 'notfound') {
    return (
      <ContentLayout header={<Header variant="h1">링크를 찾을 수 없습니다</Header>}>
        <Button onClick={() => navigate('/dashboard')}>내 링크로 돌아가기</Button>
      </ContentLayout>
    )
  }

  if (state === 'error') {
    return (
      <ContentLayout header={<Header variant="h1">통계</Header>}>
        <Alert
          type="error"
          header="통계를 불러오지 못했습니다"
          action={<Button onClick={retry}>다시 시도</Button>}
        >
          잠시 후 다시 시도해 주세요.
        </Alert>
      </ContentLayout>
    )
  }

  if (!link || !daily) {
    return (
      <Box padding="xxl" textAlign="center">
        <StatusIndicator type="loading">불러오는 중...</StatusIndicator>
      </Box>
    )
  }

  const tiles = [
    { label: '오늘 클릭 수', value: realtime ?? '-' },
    { label: '오늘 방문자 수 (UV)', value: realtimeVisitors ?? '-' },
    ...(change
      ? [
          {
            label: '어제 클릭 (전일 대비)',
            value: `${change.clicks.base.toLocaleString()} (${formatRate(change.clicks.changeRate)})`
          },
          {
            label: '어제 방문자 (전일 대비)',
            value: `${change.visitors.base.toLocaleString()} (${formatRate(change.visitors.changeRate)})`
          }
        ]
      : []),
    // link_daily_stats는 배치가 어제까지만 채워서 오늘 클릭이 빠져있다. realtime(오늘, Redis)을 더해 보정한다.
    { label: '총 클릭', value: (daily.summary.totalClicks + (realtime ?? 0)).toLocaleString() }
  ]

  return (
    <ContentLayout
      header={
        <Header
          variant="h1"
          description={link.originalUrl}
          actions={
            <SpaceBetween direction="horizontal" size="xs">
              <Button iconName="refresh" loading={refreshing} onClick={handleRefresh}>
                새로고침
              </Button>
              <Button onClick={() => navigate('/dashboard')}>내 링크</Button>
            </SpaceBetween>
          }
        >
          {link.title || link.shortUrl}
        </Header>
      }
    >
      <SpaceBetween size="l">
        <StatGrid items={tiles} columns={3} />

        <Container
          header={
            <Header
              variant="h2"
              actions={
                <SegmentedControl
                  selectedId={String(days)}
                  onChange={({ detail }) => setDays(Number(detail.selectedId))}
                  options={RANGE_OPTIONS}
                />
              }
            >
              일별 클릭/방문자 추이
            </Header>
          }
        >
          <TrendChart daily={daily.daily} />
        </Container>

        <BreakdownTable title="유입 경로" rows={referrers} nameKey="referrerCategory" />
        <BreakdownTable title="디바이스 분포" rows={deviceBreakdown?.breakdown} nameKey="value" />
        <BreakdownTable
          title="접속 국가 분포"
          rows={countryBreakdown?.breakdown.map((r) => ({ ...r, value: countryName(r.value) }))}
          nameKey="value"
        />
      </SpaceBetween>
    </ContentLayout>
  )
}

function formatRate(rate) {
  const sign = rate > 0 ? '+' : ''
  return `${sign}${rate}%`
}
