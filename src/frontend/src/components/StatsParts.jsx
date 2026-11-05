// 통계 화면 두 곳(링크 통계, 통계 대시보드)이 같이 쓰는 Cloudscape 조각
import Box from '@cloudscape-design/components/box'
import ColumnLayout from '@cloudscape-design/components/column-layout'
import Container from '@cloudscape-design/components/container'
import Header from '@cloudscape-design/components/header'
import LineChart from '@cloudscape-design/components/line-chart'
import Table from '@cloudscape-design/components/table'

const day = (iso) => new Date(`${iso}T00:00:00`)
const fmtDay = (d) => `${d.getMonth() + 1}/${d.getDate()}`

// items = [{ label, value }]
export function StatGrid({ items, columns = 4 }) {
  return (
    <Container>
      <ColumnLayout columns={columns} variant="text-grid">
        {items.map((it) => (
          <div key={it.label}>
            <Box variant="awsui-key-label">{it.label}</Box>
            <Box variant="awsui-value-large">{it.value}</Box>
          </div>
        ))}
      </ColumnLayout>
    </Container>
  )
}

// 일별 클릭 수와 방문자 수 두 줄. daily = [{ date: 'YYYY-MM-DD', clickCount, visitorCount }]
export function TrendChart({ daily }) {
  const line = (title, key) => ({
    title,
    type: 'line',
    data: daily.map((d) => ({ x: day(d.date), y: d[key] }))
  })

  return (
    <LineChart
      series={[line('클릭 수', 'clickCount'), line('방문자 수', 'visitorCount')]}
      xScaleType="time"
      i18nStrings={{
        xTickFormatter: fmtDay,
        yTickFormatter: (y) => (Number.isInteger(y) ? y.toLocaleString() : '')
      }}
      height={280}
      hideFilter
      ariaLabel="일별 클릭 수와 방문자 수 추이"
      empty={
        <Box textAlign="center" color="inherit">
          <b>해당 기간에 데이터가 없어요</b>
        </Box>
      }
    />
  )
}

// rows 가 undefined 이면 불러오는 중이다. rows = [{ [nameKey], clickCount, percentage }]
export function BreakdownTable({ title, rows, nameKey }) {
  return (
    <Table
      variant="container"
      header={<Header variant="h2">{title}</Header>}
      items={rows ?? []}
      trackBy={nameKey}
      loading={!rows}
      loadingText="불러오는 중"
      empty={
        <Box textAlign="center" color="text-body-secondary">
          해당 기간에 데이터가 없습니다.
        </Box>
      }
      columnDefinitions={[
        { id: 'name', header: '구분', cell: (r) => r[nameKey], isRowHeader: true },
        { id: 'clicks', header: '클릭 수', cell: (r) => r.clickCount.toLocaleString() },
        { id: 'rate', header: '비율', cell: (r) => `${r.percentage}%` }
      ]}
    />
  )
}
