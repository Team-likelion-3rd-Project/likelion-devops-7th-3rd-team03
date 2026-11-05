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
import { getDailyStats, compareLinks, getReferrerStats } from '../api/stats'
import { listLinks } from '../api/links'
import { ApiError } from '../api/http'
import { StatGrid, TrendChart } from '../components/StatsParts'
import { toast } from '../components/Toast'

function todayIso() {
  return new Date().toISOString().slice(0, 10)
}

function daysAgoIso(n) {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return d.toISOString().slice(0, 10)
}

const linkOption = (l) => ({
  label: l.title || l.shortUrl,
  value: l.linkId,
  description: l.shortUrl
})

const dateProps = {
  locale: 'ko',
  placeholder: 'YYYY/MM/DD',
  todayAriaLabel: '오늘',
  nextMonthAriaLabel: '다음 달',
  previousMonthAriaLabel: '이전 달'
}

// 백엔드(StatsController)에 실제로 붙는 통계 대시보드. 단건 통계는 링크 화면의
// '통계' 버튼(/links/:id/stats)을 쓰고, 여기는 여러 링크를 골라 직접 비교할 때 쓴다.
export function StatsDashboard() {
  const [links, setLinks] = useState([])
  const [linksState, setLinksState] = useState('loading') // loading | ready | error
  const [linkId, setLinkId] = useState('')
  const [compareIds, setCompareIds] = useState([])
  const [from, setFrom] = useState(daysAgoIso(13))
  const [to, setTo] = useState(todayIso())

  const [daily, setDaily] = useState(null)
  const [referrers, setReferrers] = useState(null)
  const [compareRows, setCompareRows] = useState(null)
  const [loading, setLoading] = useState(false)

  // ponytail: 목록 API가 페이지네이션이라 최근 100개만 불러옴. 그 이상이면 페이지 넘김 UI 추가 필요.
  const loadLinks = useCallback(() => {
    setLinksState('loading')
    listLinks(0, 100)
      .then((res) => {
        setLinks(res.links)
        setLinksState('ready')
      })
      .catch(() => {
        setLinksState('error')
        toast('링크 목록을 불러오지 못했습니다.', 'error')
      })
  }, [])

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
    setLoading(true)
    try {
      const [dailyRes, referrerRes] = await Promise.all([
        getDailyStats(linkId, from, to),
        getReferrerStats(linkId, from, to)
      ])
      setDaily(dailyRes)
      setReferrers(referrerRes)
      setCompareRows(compareIds.length > 0 ? await compareLinks(compareIds, from, to) : null)
    } catch (err) {
      const message = err instanceof ApiError ? err.message : '통계를 불러오지 못했습니다.'
      toast(message, 'error')
      setDaily(null)
      setReferrers(null)
      setCompareRows(null)
    } finally {
      setLoading(false)
    }
  }

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
          <Container header={<Header variant="h2">유입 경로</Header>}>
            <SpaceBetween size="l">
              <BarChart
                series={[
                  {
                    title: '클릭 수',
                    type: 'bar',
                    data: referrers.map((r) => ({ x: r.referrerCategory, y: r.clickCount }))
                  }
                ]}
                xScaleType="categorical"
                i18nStrings={{
                  yTickFormatter: (y) => (Number.isInteger(y) ? y.toLocaleString() : '')
                }}
                height={240}
                hideFilter
                hideLegend
                ariaLabel="유입 경로별 클릭 수"
                empty={
                  <Box textAlign="center" color="inherit">
                    <b>해당 기간에 클릭 기록이 없습니다.</b>
                  </Box>
                }
              />
              <Table
                variant="embedded"
                items={referrers}
                trackBy="referrerCategory"
                columnDefinitions={[
                  {
                    id: 'name',
                    header: '유입 경로',
                    cell: (r) => r.referrerCategory,
                    isRowHeader: true
                  },
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
