import { useCallback, useEffect, useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import {
  ResponsiveContainer,
  LineChart,
  Line,
  CartesianGrid,
  XAxis,
  YAxis,
  Tooltip,
  Legend,
} from 'recharts'
import { listLinks } from '../api/links'
import { getDailyStats, getReferrerStats, getDailyChange, getBreakdown, getRealtimeCount } from '../api/stats'
import { ApiError } from '../api/http'

const RANGE_OPTIONS = [
  { label: '7일', days: 7 },
  { label: '14일', days: 14 },
  { label: '30일', days: 30 },
]

function daysAgoIso(n) {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return d.toISOString().slice(0, 10)
}

export function LinkStats() {
  const { id: linkId } = useParams()

  const [link, setLink] = useState(null)
  const [notFound, setNotFound] = useState(false)
  const [days, setDays] = useState(14)

  const [daily, setDaily] = useState(null)
  const [referrers, setReferrers] = useState(null)
  const [change, setChange] = useState(null)
  const [deviceBreakdown, setDeviceBreakdown] = useState(null)
  const [regionBreakdown, setRegionBreakdown] = useState(null)
  const [realtime, setRealtime] = useState(null)
  const [realtimeVisitors, setRealtimeVisitors] = useState(null)
  const [refreshing, setRefreshing] = useState(false)

  // ponytail: 링크 단건 조회 API가 없어 목록에서 찾는다. 사용자당 링크가 100개를 넘으면 못 찾을 수 있음 — 그때 GET /api/links/{id} 추가.
  useEffect(() => {
    listLinks(0, 100).then((res) => {
      const found = res.links.find((l) => l.linkId === linkId)
      if (!found) setNotFound(true)
      else setLink(found)
    })
  }, [linkId])

  const from = daysAgoIso(days - 1)
  const to = daysAgoIso(0)

  const loadStats = useCallback(async () => {
    try {
      const [dailyRes, referrerRes, changeRes, deviceRes, regionRes] = await Promise.all([
        getDailyStats(linkId, from, to),
        getReferrerStats(linkId, from, to),
        getDailyChange(linkId),
        getBreakdown(linkId, 'DEVICE', from, to),
        getBreakdown(linkId, 'REGION', from, to),
      ])
      setDaily(dailyRes)
      setReferrers(referrerRes)
      setChange(changeRes)
      setDeviceBreakdown(deviceRes)
      setRegionBreakdown(regionRes)
    } catch (err) {
      if (err instanceof ApiError) setNotFound(true)
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

  useEffect(() => {
    let cancelled = false
    function poll() {
      getRealtimeCount(linkId).then((res) => {
        if (!cancelled) {
          setRealtime(res.realtimeClickCount)
          setRealtimeVisitors(res.realtimeUniqueVisitorCount)
        }
      })
    }
    poll()
    const timer = setInterval(poll, 10000)
    return () => {
      cancelled = true
      clearInterval(timer)
    }
  }, [linkId])

  if (notFound) {
    return (
      <div className="page">
        <p>링크를 찾을 수 없습니다.</p>
        <Link to="/dashboard">내 링크로 돌아가기</Link>
      </div>
    )
  }

  if (!link || !daily) return <div className="page">불러오는 중...</div>

  return (
    <div className="page">
      <div className="page__header">
        <div>
          <h1>{link.title || link.shortUrl}</h1>
          <p className="hero__sub" title={link.originalUrl}>
            {link.originalUrl}
          </p>
        </div>
        <div className="page__header-actions">
          <button className="btn btn--ghost" onClick={handleRefresh} disabled={refreshing}>
            {refreshing ? '새로고침 중...' : '새로고침'}
          </button>
          <Link to="/dashboard" className="btn btn--ghost">
            ← 내 링크
          </Link>
        </div>
      </div>

      <div className="stat-tiles">
        <StatTile label="오늘 클릭 수" value={realtime ?? '-'} />
        <StatTile label="오늘 방문자 수 (UV)" value={realtimeVisitors ?? '-'} />
        {change && (
          <>
            <StatTile
              label="어제 클릭 (전일 대비)"
              value={`${change.clicks.base.toLocaleString()} (${formatRate(change.clicks.changeRate)})`}
            />
            <StatTile
              label="어제 방문자 (전일 대비)"
              value={`${change.visitors.base.toLocaleString()} (${formatRate(change.visitors.changeRate)})`}
            />
          </>
        )}
        {/* link_daily_stats는 배치가 어제까지만 채워서 오늘 클릭이 빠져있다. realtime(오늘, Redis)을 더해 보정한다. */}
        <StatTile label="총 클릭" value={(daily.summary.totalClicks + (realtime ?? 0)).toLocaleString()} />
      </div>

      <div className="card chart-card">
        <div className="chart-card__header">
          <h2>일별 클릭/방문자 추이</h2>
          <div className="segmented">
            {RANGE_OPTIONS.map((opt) => (
              <button
                key={opt.days}
                className={`segmented__item ${days === opt.days ? 'is-active' : ''}`}
                onClick={() => setDays(opt.days)}
              >
                {opt.label}
              </button>
            ))}
          </div>
        </div>

        <div className="viz-root">
          <ResponsiveContainer width="100%" height={280}>
            <LineChart data={daily.daily} margin={{ top: 8, right: 16, left: -12, bottom: 0 }}>
              <CartesianGrid vertical={false} stroke="var(--gridline)" />
              <XAxis
                dataKey="date"
                tickFormatter={(d) => d.slice(5)}
                stroke="var(--muted)"
                tick={{ fontSize: 12, fill: 'var(--muted)' }}
                axisLine={{ stroke: 'var(--axis)' }}
                tickLine={false}
              />
              <YAxis
                allowDecimals={false}
                stroke="var(--muted)"
                tick={{ fontSize: 12, fill: 'var(--muted)' }}
                axisLine={false}
                tickLine={false}
                width={32}
              />
              <Tooltip
                contentStyle={{
                  background: 'var(--surface-1)',
                  border: '1px solid var(--border)',
                  borderRadius: 8,
                  fontSize: 13,
                }}
                labelStyle={{ color: 'var(--text-primary)' }}
              />
              <Legend wrapperStyle={{ fontSize: 13 }} />
              <Line
                type="monotone"
                dataKey="clickCount"
                name="클릭 수"
                stroke="var(--series-1)"
                strokeWidth={2}
                dot={false}
                activeDot={{ r: 4 }}
              />
              <Line
                type="monotone"
                dataKey="visitorCount"
                name="방문자 수"
                stroke="var(--series-2)"
                strokeWidth={2}
                dot={false}
                activeDot={{ r: 4 }}
              />
            </LineChart>
          </ResponsiveContainer>
        </div>
      </div>

      <div className="card">
        <h2>유입 경로</h2>
        <BreakdownTable rows={referrers} nameKey="referrerCategory" />
      </div>

      <div className="card">
        <h2>디바이스 분포</h2>
        <BreakdownTable rows={deviceBreakdown?.breakdown} nameKey="value" />
      </div>

      <div className="card">
        <h2>접속 지역 분포</h2>
        <BreakdownTable rows={regionBreakdown?.breakdown} nameKey="value" />
      </div>
    </div>
  )
}

function BreakdownTable({ rows, nameKey }) {
  if (!rows) return <p className="empty-state empty-state--inline">불러오는 중...</p>
  if (rows.length === 0) return <p className="empty-state empty-state--inline">해당 기간에 데이터가 없습니다.</p>
  return (
    <table className="table">
      <thead>
        <tr>
          <th>구분</th>
          <th>클릭 수</th>
          <th>비율</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r[nameKey]}>
            <td>{r[nameKey]}</td>
            <td>{r.clickCount.toLocaleString()}</td>
            <td>{r.percentage}%</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function formatRate(rate) {
  const sign = rate > 0 ? '+' : ''
  return `${sign}${rate}%`
}

function StatTile({ label, value }) {
  return (
    <div className="card stat-tile">
      <span className="stat-tile__label">{label}</span>
      <span className="stat-tile__value">{value}</span>
    </div>
  )
}