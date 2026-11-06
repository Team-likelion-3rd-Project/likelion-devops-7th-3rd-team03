import { useCallback, useEffect, useState } from 'react'
import Alert from '@cloudscape-design/components/alert'
import BarChart from '@cloudscape-design/components/bar-chart'
import Box from '@cloudscape-design/components/box'
import Button from '@cloudscape-design/components/button'
import ColumnLayout from '@cloudscape-design/components/column-layout'
import Container from '@cloudscape-design/components/container'
import ContentLayout from '@cloudscape-design/components/content-layout'
import DatePicker from '@cloudscape-design/components/date-picker'
import FormField from '@cloudscape-design/components/form-field'
import Header from '@cloudscape-design/components/header'
import Multiselect from '@cloudscape-design/components/multiselect'
import Select from '@cloudscape-design/components/select'
import SpaceBetween from '@cloudscape-design/components/space-between'
import Table from '@cloudscape-design/components/table'
import {
  getDailyStats,
  compareLinks,
  getReferrerStats,
  getBreakdown,
  hourlyClicks,
  kstDay
} from '../api/stats'
import { listLinks } from '../api/links'
import { ApiError } from '../api/http'
import { useWorkspace } from '../context/WorkspaceContext'
import { RequireWorkspace } from '../components/RequireWorkspace'
import { StatGrid, TrendChart } from '../components/StatsParts'
import { toast } from '../components/Toast'
import { dimensionName, mergeByName, osName } from '../statsLabels'

const linkOption = (l) => ({
  label: l.title || l.shortUrl,
  value: l.linkId,
  description: l.shortUrl
})

const wholeNumbers = { yTickFormatter: (y) => (Number.isInteger(y) ? y.toLocaleString() : '') }

const dateProps = {
  locale: 'ko',
  placeholder: 'YYYY/MM/DD',
  todayAriaLabel: '오늘',
  nextMonthAriaLabel: '다음 달',
  previousMonthAriaLabel: '이전 달'
}

// 백엔드(워크스페이스 통계 API)에 실제로 붙는 통계 대시보드. 단건 통계는 링크 화면의
// '통계' 버튼(/links/:id/stats)을 쓰고, 여기는 여러 링크를 골라 직접 비교할 때 쓴다.
export function StatsDashboard() {
  return (
    <RequireWorkspace>
      <StatsView />
    </RequireWorkspace>
  )
}

function StatsView() {
  const { current } = useWorkspace()
  const [links, setLinks] = useState([])
  const [linksState, setLinksState] = useState('loading') // loading | ready | error
  const [linkId, setLinkId] = useState('')
  const [compareIds, setCompareIds] = useState([])
  const [from, setFrom] = useState(kstDay(13))
  const [to, setTo] = useState(kstDay(0))

  const [daily, setDaily] = useState(null)
  const [referrers, setReferrers] = useState(null)
  const [dist, setDist] = useState(null) // { os, browser, hours }
  const [compareRows, setCompareRows] = useState(null)
  const [loading, setLoading] = useState(false)

  // 팀당 링크는 100개로 제한되므로(#204) 한 번에 100개를 불러오면 그 팀의 링크가 모두 들어온다.
  const loadLinks = useCallback(() => {
    setLinksState('loading')
    listLinks(current, 0, 100)
      .then((res) => {
        setLinks(res.links)
        setLinksState('ready')
      })
      .catch(() => {
        setLinksState('error')
        toast('링크 목록을 불러오지 못했습니다.', 'error')
      })
  }, [current])

  useEffect(() => {
    loadLinks()
  }, [loadLinks])

  async function handleLoad() {
    if (!linkId) {
      toast('조회할 링크를 선택해주세요.', 'error')
      return
    }
    if (!from || !to || from > to) {
      toast('시작일과 종료일을 확인해주세요.', 'error')
      return
    }
    // 서버 제한(WorkspaceStatsService): 기간은 90일 미만, 비교 대상은 20개까지. 넘으면 400이라 미리 알려 준다.
    if ((Date.parse(to) - Date.parse(from)) / 86_400_000 >= 90) {
      toast('조회 기간은 90일 미만으로 선택해주세요.', 'error')
      return
    }
    if (compareIds.length > 20) {
      toast('비교할 링크는 20개까지 고를 수 있습니다.', 'error')
      return
    }
    setLoading(true)
    try {
      const [dailyRes, referrerRes, osRes, browserRes, hourRes] = await Promise.all([
        getDailyStats(current, linkId, from, to),
        getReferrerStats(current, linkId, from, to),
        getBreakdown(current, linkId, 'OS', from, to),
        getBreakdown(current, linkId, 'BROWSER', from, to),
        getBreakdown(current, linkId, 'HOUR', from, to)
      ])
      setDaily(dailyRes)
      setReferrers(referrerRes)
      setDist({
        os: mergeByName(osRes.breakdown, osName),
        browser: mergeByName(browserRes.breakdown, dimensionName),
        hours: hourlyClicks(hourRes.breakdown)
      })
      setCompareRows(
        compareIds.length > 0
          ? withLinkInfo(await compareLinks(current, compareIds, from, to))
          : null
      )
    } catch (err) {
      const message = err instanceof ApiError ? err.message : '통계를 불러오지 못했습니다.'
      toast(message, 'error')
      setDaily(null)
      setReferrers(null)
      setDist(null)
      setCompareRows(null)
    } finally {
      setLoading(false)
    }
  }

  // 비교 응답에는 슬러그·제목이 없어서 불러와 둔 링크 목록에서 붙인다
  const withLinkInfo = (rows) =>
    rows.map((r) => {
      const link = links.find((l) => l.linkId === r.linkId)
      return { ...r, slug: link?.shortUrl?.split('/').pop() ?? '', title: link?.title }
    })

  const options = links.map(linkOption)

  return (
    <ContentLayout
      header={
        <Header variant="h1" description="링크별 클릭 통계를 확인합니다.">
          분석
        </Header>
      }
    >
      <SpaceBetween size="l">
        {linksState === 'error' && (
          <Alert
            type="error"
            header="링크 목록을 불러오지 못했습니다"
            action={<Button onClick={loadLinks}>다시 시도</Button>}
          />
        )}

        <form
          onSubmit={(e) => {
            e.preventDefault()
            handleLoad()
          }}
        >
          <Container header={<Header variant="h2">조회 조건</Header>}>
            <SpaceBetween size="l">
              <ColumnLayout columns={3}>
                <FormField label="링크">
                  <Select
                    selectedOption={options.find((o) => o.value === linkId) ?? null}
                    onChange={({ detail }) => setLinkId(detail.selectedOption.value)}
                    options={options}
                    placeholder="링크를 선택하세요"
                    statusType={linksState === 'loading' ? 'loading' : 'finished'}
                    loadingText="불러오는 중"
                    empty="링크가 없습니다"
                    filteringType="auto"
                  />
                </FormField>
                <FormField label="시작일">
                  <DatePicker
                    {...dateProps}
                    value={from}
                    onChange={({ detail }) => setFrom(detail.value)}
                  />
                </FormField>
                <FormField label="종료일">
                  <DatePicker
                    {...dateProps}
                    value={to}
                    onChange={({ detail }) => setTo(detail.value)}
                  />
                </FormField>
              </ColumnLayout>

              <FormField label="비교할 링크" constraintText="선택">
                <Multiselect
                  selectedOptions={options.filter((o) => compareIds.includes(o.value))}
                  onChange={({ detail }) =>
                    setCompareIds(detail.selectedOptions.map((o) => o.value))
                  }
                  options={options}
                  placeholder="비교할 링크를 고르세요"
                  deselectAriaLabel={(o) => `${o.label} 제거`}
                  empty="비교할 링크가 없습니다"
                  filteringType="auto"
                />
              </FormField>

              <Button variant="primary" formAction="submit" loading={loading}>
                조회
              </Button>
            </SpaceBetween>
          </Container>
        </form>

        {!daily && !loading && (
          <Box textAlign="center" color="text-body-secondary" padding="l">
            링크를 고르고 조회를 눌러 주세요.
          </Box>
        )}

        {daily && (
          <>
            <StatGrid
              columns={5}
              items={[
                { label: '총 클릭', value: daily.summary.totalClicks.toLocaleString() },
                {
                  label: '일별 방문자 합계',
                  value: daily.summary.sumOfDailyVisitors.toLocaleString()
                },
                { label: '일평균 클릭', value: daily.summary.avgDailyClicks.toLocaleString() },
                { label: '최고 클릭일', value: daily.summary.peakClicks.toLocaleString() },
                { label: '활동일 수', value: daily.summary.activeDays.toLocaleString() }
              ]}
            />

            <Container header={<Header variant="h2">일별 클릭/방문자 추이</Header>}>
              <TrendChart daily={daily.daily} />
            </Container>
          </>
        )}

        {referrers && (
          <Box color="text-body-secondary" fontSize="body-s">
            유입 경로·운영체제·브라우저·시간대 분포는 어제까지 집계예요. 시간대는 한국 시간(KST)
            기준이에요.
          </Box>
        )}

        {referrers && <BreakdownCard title="유입 경로" rows={referrers} />}

        {dist && (
          <>
            <BreakdownCard title="운영체제(OS)" rows={dist.os} />
            <BreakdownCard title="브라우저" rows={dist.browser} />
            <Container header={<Header variant="h2">시간대별 클릭</Header>}>
              {dist.hours.some((h) => h.clickCount > 0) ? (
                <BarChart
                  series={[
                    {
                      title: '클릭 수',
                      type: 'bar',
                      data: dist.hours.map((h) => ({ x: `${h.hour}시`, y: h.clickCount }))
                    }
                  ]}
                  xScaleType="categorical"
                  i18nStrings={wholeNumbers}
                  height={240}
                  hideFilter
                  hideLegend
                  ariaLabel="시간대별 클릭 수"
                />
              ) : (
                <Box textAlign="center" color="text-body-secondary" padding="l">
                  해당 기간에 클릭 기록이 없습니다.
                </Box>
              )}
            </Container>
          </>
        )}

        {compareRows && (
          <Table
            variant="container"
            header={<Header variant="h2">링크 비교</Header>}
            items={compareRows}
            trackBy="linkId"
            empty={
              <Box textAlign="center" color="text-body-secondary">
                비교할 링크를 찾을 수 없습니다.
              </Box>
            }
            columnDefinitions={[
              { id: 'slug', header: '슬러그', cell: (r) => `/${r.slug}`, isRowHeader: true },
              { id: 'title', header: '제목', cell: (r) => r.title || '-' },
              { id: 'total', header: '총 클릭', cell: (r) => r.totalClicks.toLocaleString() },
              {
                id: 'visitors',
                header: '일별 방문자 합계',
                cell: (r) => r.sumOfDailyVisitors.toLocaleString()
              },
              {
                id: 'peak',
                header: '최고 일 클릭',
                cell: (r) => r.peakDailyClicks.toLocaleString()
              }
            ]}
          />
        )}
      </SpaceBetween>
    </ContentLayout>
  )
}

// 분포 하나(막대 그래프 + 표). rows: [{ value, clickCount, percentage }]
function BreakdownCard({ title, rows }) {
  return (
    <Container header={<Header variant="h2">{title}</Header>}>
      <SpaceBetween size="l">
        <BarChart
          series={[
            {
              title: '클릭 수',
              type: 'bar',
              data: rows.map((r) => ({ x: r.value, y: r.clickCount }))
            }
          ]}
          xScaleType="categorical"
          i18nStrings={wholeNumbers}
          height={240}
          hideFilter
          hideLegend
          ariaLabel={`${title}별 클릭 수`}
          empty={
            <Box textAlign="center" color="inherit">
              <b>해당 기간에 클릭 기록이 없습니다.</b>
            </Box>
          }
        />
        <Table
          variant="embedded"
          items={rows}
          trackBy="value"
          columnDefinitions={[
            { id: 'name', header: title, cell: (r) => r.value, isRowHeader: true },
            { id: 'clicks', header: '클릭 수', cell: (r) => r.clickCount.toLocaleString() },
            { id: 'rate', header: '비율', cell: (r) => `${r.percentage}%` }
          ]}
          empty={
            <Box textAlign="center" color="text-body-secondary">
              해당 기간에 클릭 기록이 없습니다.
            </Box>
          }
        />
      </SpaceBetween>
    </Container>
  )
}
