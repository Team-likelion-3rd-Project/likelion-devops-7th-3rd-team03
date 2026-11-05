// 홈 왼쪽 "운영 현황": 지표, 클릭 추이, 인기 링크, 방문자 분포 (#210). 지금 선택한 워크스페이스 기준이다.
// 하나라도 실패하면 이 영역 전체에 오류와 다시 시도를 보여 준다.
import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import Alert from '@cloudscape-design/components/alert'
import Box from '@cloudscape-design/components/box'
import Button from '@cloudscape-design/components/button'
import Container from '@cloudscape-design/components/container'
import Header from '@cloudscape-design/components/header'
import Link from '@cloudscape-design/components/link'
import SegmentedControl from '@cloudscape-design/components/segmented-control'
import SpaceBetween from '@cloudscape-design/components/space-between'
import StatusIndicator from '@cloudscape-design/components/status-indicator'
import Table from '@cloudscape-design/components/table'
import { listLinks } from '../api/links'
import {
  getLinkTotals,
  getTeamBreakdown,
  getTeamDaily,
  isActiveLink,
  kstDay,
  topLinks
} from '../api/stats'
import { BreakdownTable, StatGrid, TrendChart } from './StatsParts'

const PERIODS = [
  { id: '1', text: '1일' },
  { id: '7', text: '7일' },
  { id: '14', text: '14일' },
  { id: '30', text: '30일' }
]

export function TeamOverview({ ws, reloadKey }) {
  const navigate = useNavigate()
  const [days, setDays] = useState(7)
  const [state, setState] = useState('loading') // loading | ready | error
  const [data, setData] = useState(null)
  const latest = useRef(0)

  const load = useCallback(async () => {
    const request = ++latest.current
    setState('loading')
    // 기간은 오늘을 포함한다(오늘 값은 서버가 Redis로 채움). 분포는 배치 집계라 어제까지다.
    const from = kstDay(days - 1)
    const to = kstDay(0)
    const distFrom = kstDay(days)
    const distTo = kstDay(1)
    try {
      // 팀당 링크는 100개로 제한되므로(#204) 100개를 불러오면 팀의 링크가 모두 들어온다.
      const { links } = await listLinks(ws, 0, 100)
      const [daily, totals, referrers, devices] = await Promise.all([
        getTeamDaily(ws, from, to),
        getLinkTotals(
          ws,
          links.map((l) => l.linkId),
          from,
          to
        ),
        getTeamBreakdown(ws, 'REFERRER', distFrom, distTo),
        getTeamBreakdown(ws, 'DEVICE', distFrom, distTo)
      ])
      if (request !== latest.current) return // 기간을 빨리 바꿨다면 마지막 요청만 반영한다
      setData({
        linkCount: links.length,
        activeCount: links.filter(isActiveLink).length,
        daily,
        top: topLinks(links, totals),
        referrers,
        devices
      })
      setState('ready')
    } catch {
      if (request === latest.current) setState('error')
    }
  }, [ws, days])

  useEffect(() => {
    load()
  }, [load, reloadKey]) // reloadKey: 홈에서 링크를 만들면 다시 불러온다

  const header = (
    <Header
      variant="h2"
      description={state === 'loading' && data ? '불러오는 중…' : undefined}
      actions={
        <SegmentedControl
          selectedId={String(days)}
          onChange={({ detail }) => setDays(Number(detail.selectedId))}
          options={PERIODS}
        />
      }
    >
      운영 현황
    </Header>
  )

  if (state === 'error') {
    return (
      <Container header={header}>
        <Alert
          type="error"
          header="운영 현황을 불러오지 못했어요"
          action={<Button onClick={load}>다시 시도</Button>}
        >
          잠시 후 다시 시도해 주세요.
        </Alert>
      </Container>
    )
  }

  if (!data) {
    return (
      <Container header={header}>
        <Box textAlign="center" padding="l">
          <StatusIndicator type="loading">불러오는 중</StatusIndicator>
        </Box>
      </Container>
    )
  }

  if (data.linkCount === 0) {
    return (
      <Container header={header}>
        <Box textAlign="center" color="text-body-secondary" padding="l">
          아직 링크가 없어요. 오른쪽에서 첫 링크를 만들면 클릭과 방문자가 여기에 쌓여요.
        </Box>
      </Container>
    )
  }

  const { summary } = data.daily
  return (
    <SpaceBetween size="l">
      <Container header={header}>
        <SpaceBetween size="l">
          <StatGrid
            columns={3}
            items={[
              { label: '활성 링크', value: data.activeCount.toLocaleString() },
              { label: '클릭', value: summary.totalClicks.toLocaleString() },
              { label: '방문자 (일별 합계)', value: summary.sumOfDailyVisitors.toLocaleString() }
            ]}
          />
          {data.daily.daily.length > 1 ? (
            <TrendChart daily={data.daily.daily} />
          ) : (
            // 하루 값은 점이 하나라 선이 그려지지 않는다. 숫자는 위 카드가 보여 준다.
            <Box textAlign="center" color="text-body-secondary" padding="l">
              1일은 위 숫자로 확인하세요. 추이 그래프는 7일부터 보여요.
            </Box>
          )}
        </SpaceBetween>
      </Container>

      <Table
        variant="container"
        header={<Header variant="h2">인기 링크</Header>}
        items={data.top}
        trackBy="linkId"
        empty={
          <Box textAlign="center" color="text-body-secondary">
            해당 기간에 클릭된 링크가 없어요.
          </Box>
        }
        columnDefinitions={[
          {
            id: 'link',
            header: '링크',
            isRowHeader: true,
            cell: (l) => (
              <Link
                href={`/links/${l.linkId}/stats`}
                onFollow={(e) => {
                  e.preventDefault()
                  navigate(`/links/${l.linkId}/stats`)
                }}
              >
                {l.title || l.shortUrl}
              </Link>
            )
          },
          { id: 'clicks', header: '클릭 수', cell: (l) => l.totalClicks.toLocaleString() }
        ]}
      />

      <BreakdownTable title="유입 경로 (어제까지)" rows={data.referrers} nameKey="value" />
      <BreakdownTable title="디바이스 (어제까지)" rows={data.devices} nameKey="value" />
    </SpaceBetween>
  )
}
