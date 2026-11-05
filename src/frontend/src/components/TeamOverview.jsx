// 홈 왼쪽 "운영 현황": 지표, 클릭 추이, 인기 링크, 방문자 분포 (#210). 지금 선택한 워크스페이스 기준이다.
// 영역마다 따로 불러오고 따로 실패한다. 한 영역이 실패해도 다른 영역은 보이고, 실패한 영역에만 다시 시도를 둔다.
import { useCallback, useState } from 'react'
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
  getTeamBreakdown,
  getTeamDaily,
  isActiveLink,
  loadTopLinks,
  periodRange
} from '../api/stats'
import { useLoad } from '../hooks/useLoad'
import { countryName } from '../statsLabels'
import { BreakdownTable, StatGrid, TrendChart } from './StatsParts'

const PERIODS = [
  { id: '1', text: '1일' },
  { id: '7', text: '7일' },
  { id: '14', text: '14일' },
  { id: '30', text: '30일' }
]

// 팀당 링크는 100개로 제한되므로(#204) 100개를 불러오면 팀의 링크가 모두 들어온다.
const fetchLinks = (ws) => listLinks(ws, 0, 100).then((res) => res.links)
const toCountryRow = (r) => ({ ...r, value: countryName(r.value) })

export function TeamOverview({ ws, reloadKey }) {
  const [days, setDays] = useState(7)
  const loadLinks = useCallback(() => fetchLinks(ws), [ws])
  const links = useLoad(loadLinks, reloadKey)
  const range = periodRange(days)

  const header = (
    <Header
      variant="h2"
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

  if (links.state === 'ready' && links.data.length === 0) {
    return (
      <Container header={header}>
        <Box textAlign="center" color="text-body-secondary" padding="l">
          아직 링크가 없어요. 오른쪽에서 첫 링크를 만들어 보세요. 클릭과 방문자가 여기에 쌓여요.
        </Box>
      </Container>
    )
  }

  const activeCount =
    links.state === 'error' || !links.data ? null : links.data.filter(isActiveLink).length

  return (
    <SpaceBetween size="l">
      <Container header={header}>
        <Metrics ws={ws} range={range} reloadKey={reloadKey} activeCount={activeCount} />
      </Container>
      <TopLinks ws={ws} links={links} range={range} />
      <Distribution ws={ws} range={range} reloadKey={reloadKey} />
    </SpaceBetween>
  )
}

function Loading() {
  return (
    <Box textAlign="center" padding="l">
      <StatusIndicator type="loading">불러오는 중</StatusIndicator>
    </Box>
  )
}

function LoadError({ header, onRetry }) {
  return (
    <Alert type="error" header={header} action={<Button onClick={onRetry}>다시 시도</Button>}>
      잠시 후 다시 시도해 주세요.
    </Alert>
  )
}

const count = (n) => (n == null ? '-' : n.toLocaleString())

// 지표 카드와 클릭 추이. 기간은 오늘을 포함한다(오늘 값은 서버가 Redis로 채운다).
function Metrics({ ws, range, reloadKey, activeCount }) {
  const { from, to } = range
  const load = useCallback(() => getTeamDaily(ws, from, to), [ws, from, to])
  const daily = useLoad(load, reloadKey)
  const shown = daily.state === 'error' ? null : daily.data

  return (
    <SpaceBetween size="l">
      <StatGrid
        columns={3}
        items={[
          { label: '활성 링크', value: count(activeCount) },
          { label: '클릭', value: count(shown?.summary.totalClicks) },
          { label: '방문자 (링크별 합계)', value: count(shown?.summary.sumOfDailyVisitors) }
        ]}
      />
      {daily.state === 'error' && (
        <LoadError header="클릭 추이를 불러오지 못했어요" onRetry={daily.reload} />
      )}
      {!shown && daily.state === 'loading' && <Loading />}
      {shown &&
        (shown.daily.length > 1 ? (
          <TrendChart daily={shown.daily} />
        ) : (
          // 하루 값은 점이 하나라 선이 그려지지 않는다. 숫자는 위 카드가 보여 준다.
          <Box textAlign="center" color="text-body-secondary" padding="l">
            1일은 위 숫자로 확인하세요. 추이 그래프는 7일부터 보여요.
          </Box>
        ))}
    </SpaceBetween>
  )
}

// 인기 링크는 링크 목록(links)의 클릭을 합쳐 만들어서, 목록이 실패하면 이 영역도 목록을 다시 불러온다.
function TopLinks({ ws, links, range }) {
  const title = <Header variant="h2">인기 링크</Header>
  if (links.state === 'error') {
    return (
      <Container header={title}>
        <LoadError header="링크 목록을 불러오지 못했어요" onRetry={links.reload} />
      </Container>
    )
  }
  if (!links.data) {
    return (
      <Container header={title}>
        <Loading />
      </Container>
    )
  }
  return <TopLinksTable ws={ws} links={links.data} range={range} title={title} />
}

function TopLinksTable({ ws, links, range, title }) {
  const navigate = useNavigate()
  const { from, to } = range
  const load = useCallback(
    () => loadTopLinks(ws, links, from, to, () => fetchLinks(ws)),
    [ws, links, from, to]
  )
  const top = useLoad(load)

  if (top.state === 'error') {
    return (
      <Container header={title}>
        <LoadError header="인기 링크를 불러오지 못했어요" onRetry={top.reload} />
      </Container>
    )
  }
  return (
    <Table
      variant="container"
      header={title}
      items={top.data ?? []}
      trackBy="linkId"
      loading={!top.data}
      loadingText="불러오는 중"
      empty={
        <Box textAlign="center" color="text-body-secondary">
          아직 클릭이 없어요.
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
  )
}

// 방문자 분포는 배치 집계라 어제까지다. 표마다 따로 불러오고 따로 실패한다.
function Distribution({ ws, range, reloadKey }) {
  return (
    <SpaceBetween size="s">
      <Box color="text-body-secondary" fontSize="body-s">
        방문자 분포는 어제까지 집계예요. 오늘 클릭은 하루가 지나면 반영돼요.
      </Box>
      <SpaceBetween size="l">
        <DistTable ws={ws} range={range} reloadKey={reloadKey} type="REFERRER" title="유입 경로" />
        <DistTable ws={ws} range={range} reloadKey={reloadKey} type="DEVICE" title="디바이스" />
        <DistTable
          ws={ws}
          range={range}
          reloadKey={reloadKey}
          type="COUNTRY"
          title="접속 국가"
          mapRow={toCountryRow}
        />
      </SpaceBetween>
    </SpaceBetween>
  )
}

function DistTable({ ws, range, reloadKey, type, title, mapRow }) {
  const { distFrom, distTo } = range
  const load = useCallback(
    () => getTeamBreakdown(ws, type, distFrom, distTo),
    [ws, type, distFrom, distTo]
  )
  const res = useLoad(load, reloadKey)

  if (res.state === 'error') {
    return (
      <Container header={<Header variant="h2">{title}</Header>}>
        <LoadError header={`${title} 정보를 불러오지 못했어요`} onRetry={res.reload} />
      </Container>
    )
  }
  const rows = res.data && (mapRow ? res.data.map(mapRow) : res.data)
  return <BreakdownTable title={title} rows={rows} nameKey="value" />
}
