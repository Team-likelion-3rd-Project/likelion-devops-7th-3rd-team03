import { useEffect, useState } from 'react'
import {
  ResponsiveContainer,
  LineChart,
  Line,
  BarChart,
  Bar,
  CartesianGrid,
  XAxis,
  YAxis,
  Tooltip,
  Legend,
} from 'recharts'
import { getDailyStats, compareLinks, getReferrerStats } from '../api/stats'
import { listLinks } from '../api/links'
import { ApiError } from '../api/http'
import { toast } from '../components/Toast'

function todayIso() {
  return new Date().toISOString().slice(0, 10)
}

function daysAgoIso(n) {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return d.toISOString().slice(0, 10)
}

// 백엔드(StatsController)에 실제로 붙는 통계 대시보드. 단건 통계는 대시보드의
// '통계' 버튼(/links/:id/stats)을 쓰고, 여기는 여러 링크를 골라 직접 비교할 때 쓴다.
export function StatsDashboard() {
  const [links, setLinks] = useState([])
  const [linkId, setLinkId] = useState('')
  const [compareIds, setCompareIds] = useState([])
  const [from, setFrom] = useState(daysAgoIso(13))
  const [to, setTo] = useState(todayIso())

  const [daily, setDaily] = useState(null)
  const [referrers, setReferrers] = useState(null)
  const [compareRows, setCompareRows] = useState(null)
  const [loading, setLoading] = useState(false)

  // ponytail: 목록 API가 페이지네이션이라 최근 100개만 불러옴. 그 이상이면 페이지 넘김 UI 추가 필요.
  useEffect(() => {
    listLinks(0, 100)
      .then((res) => setLinks(res.links))
      .catch(() => toast('링크 목록을 불러오지 못했습니다.', 'error'))
  }, [])

  async function handleLoad(e) {
    e.preventDefault()
    if (!linkId) {
      toast('조회할 링크를 선택해주세요.', 'error')
      return
    }
    setLoading(true)
    try {
      const [dailyRes, referrerRes] = await Promise.all([
        getDailyStats(linkId, from, to),
        getReferrerStats(linkId, from, to),
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

  return (
    <div className="page">
      <div className="page__header">
        <div>
          <h1>통계 대시보드</h1>
          <p className="hero__sub">실제 백엔드(StatsController)에서 조회한 통계입니다.</p>
        </div>
      </div>

      <form className="card" onSubmit={handleLoad} style={{ display: 'grid', gap: 16 }}>
        <div style={{ display: 'grid', gap: 12, gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))' }}>
          <label className="field">
            <span>링크</span>
            <select value={linkId} onChange={(e) => setLinkId(e.target.value)}>
              <option value="">링크를 선택하세요</option>
              {links.map((l) => (
                <option key={l.linkId} value={l.linkId}>
                  {l.title || l.shortUrl}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>시작일</span>
            <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} required />
          </label>
          <label className="field">
            <span>종료일</span>
            <input type="date" value={to} onChange={(e) => setTo(e.target.value)} required />
          </label>
        </div>

        <div className="field">
          <span>비교할 링크 (선택, 클릭해서 토글)</span>
          <div className="chip-group">
            {links.length === 0 && <span className="form-hint">비교할 링크가 없습니다.</span>}
            {links.map((l) => {
              const active = compareIds.includes(l.linkId)
              return (
                <button
                  key={l.linkId}
                  type="button"
                  className={`chip ${active ? 'chip--active' : ''}`}
                  onClick={() =>
                    setCompareIds((prev) =>
                      active ? prev.filter((id) => id !== l.linkId) : [...prev, l.linkId]
                    )
                  }
                >
                  {l.title || l.shortUrl}
                </button>
              )
            })}
          </div>
        </div>

        <button className="btn btn--primary" disabled={loading} style={{ justifySelf: 'start' }}>
          {loading ? '조회 중...' : '조회'}
        </button>
      </form>

      {daily && (
        <>
          <div className="stat-tiles">
            <StatTile label="총 클릭" value={daily.summary.totalClicks.toLocaleString()} />
            <StatTile label="일별 방문자 합계" value={daily.summary.sumOfDailyVisitors.toLocaleString()} />
            <StatTile label="일평균 클릭" value={daily.summary.avgDailyClicks.toLocaleString()} />
            <StatTile label="최고 클릭일" value={daily.summary.peakClicks.toLocaleString()} />
            <StatTile label="활동일 수" value={daily.summary.activeDays.toLocaleString()} />
          </div>

          <div className="card chart-card">
            <div className="chart-card__header">
              <h2>일별 클릭/방문자 추이</h2>
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
        </>
      )}

      {referrers && (
        <div className="card chart-card">
          <div className="chart-card__header">
            <h2>유입 경로</h2>
          </div>
          {referrers.length === 0 ? (
            <p className="empty-state empty-state--inline">해당 기간에 클릭 기록이 없습니다.</p>
          ) : (
            <>
              <div className="viz-root">
                <ResponsiveContainer width="100%" height={240}>
                  <BarChart data={referrers} margin={{ top: 8, right: 16, left: -12, bottom: 0 }}>
                    <CartesianGrid vertical={false} stroke="var(--gridline)" />
                    <XAxis
                      dataKey="referrerCategory"
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
                    <Bar dataKey="clickCount" name="클릭 수" fill="var(--series-1)" radius={[4, 4, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
              <table className="table">
                <thead>
                  <tr>
                    <th>유입 경로</th>
                    <th>클릭 수</th>
                    <th>비율</th>
                  </tr>
                </thead>
                <tbody>
                  {referrers.map((r) => (
                    <tr key={r.referrerCategory}>
                      <td>{r.referrerCategory}</td>
                      <td>{r.clickCount.toLocaleString()}</td>
                      <td>{r.percentage}%</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </div>
      )}

      {compareRows && (
        <div className="card">
          <h2>링크 비교</h2>
          {compareRows.length === 0 ? (
            <p className="empty-state empty-state--inline">비교할 링크를 찾을 수 없습니다.</p>
          ) : (
            <table className="table">
              <thead>
                <tr>
                  <th>슬러그</th>
                  <th>제목</th>
                  <th>총 클릭</th>
                  <th>일별 방문자 합계</th>
                  <th>최고 일 클릭</th>
                </tr>
              </thead>
              <tbody>
                {compareRows.map((r) => (
                  <tr key={r.linkId}>
                    <td>/{r.slug}</td>
                    <td>{r.title || '-'}</td>
                    <td>{r.totalClicks.toLocaleString()}</td>
                    <td>{r.sumOfDailyVisitors.toLocaleString()}</td>
                    <td>{r.peakDailyClicks.toLocaleString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </div>
  )
}

function StatTile({ label, value }) {
  return (
    <div className="card stat-tile">
      <span className="stat-tile__label">{label}</span>
      <span className="stat-tile__value">{value}</span>
    </div>
  )
}
