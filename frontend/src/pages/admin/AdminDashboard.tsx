import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { adminApi, type ActivityLog, type ActivitySummary, type ActivityType } from '../../api'
import { useApp } from '../../context/AppContext'
import { ADMIN_SECTIONS } from './AdminLayout'
import { describe, localIso, resultClass } from './activityFormat'

const TYPE_LABEL: Partial<Record<ActivityType, string>> = {
  LOGIN: '로그인', SEARCH: '검색', PRODUCT_VIEW: '상품 조회', ORDER_CREATE: '주문', IDEMPOTENT_REPLAY: '멱등성 재생',
  PAYMENT: '결제', ORDER_VIEW: '주문 조회', ADMIN_ACTION: '관리자 작업', ERROR: '서버 에러', SIGNUP: '회원가입',
}

export function StatCards({ summary }: { summary: ActivitySummary | null }) {
  const s = summary
  return (
    <div className="stats">
      <div className="stat"><span>총 요청</span><strong>{s ? s.totalRequests.toLocaleString() : '–'}</strong></div>
      <div className="stat"><span>주문 성공</span><strong>{s ? s.orders.toLocaleString() : '–'}</strong></div>
      <div className={`stat ${s && s.serverErrors > 0 ? 'alert' : ''}`}>
        <span>서버 에러율 (5xx)</span><strong>{s ? `${(s.errorRate * 100).toFixed(1)}%` : '–'}</strong>
        {s && <small>5xx {s.serverErrors}건 · 4xx {s.clientErrors}건</small>}
      </div>
      <div className="stat"><span>평균 응답시간</span><strong>{s ? `${Math.round(s.avgLatencyMs)}ms` : '–'}</strong></div>
    </div>
  )
}

export function AdminDashboard() {
  const { fail } = useApp()
  const [summary, setSummary] = useState<ActivitySummary | null>(null)
  const [recent, setRecent] = useState<ActivityLog[] | null>(null)

  const load = () => {
    const from = localIso(new Date(Date.now() - 86_400_000))
    adminApi.activitySummary({ from }).then(r => setSummary(r.data)).catch(fail)
    adminApi.activity({ from }, 0, 8).then(r => setRecent(r.data.content)).catch(fail)
  }
  useEffect(load, [])

  const max = Math.max(1, ...Object.values(summary?.byType ?? {}).map(Number))

  return (
    <div className="admin-body">
      <StatCards summary={summary} />

      <div className="two-col">
        <section className="panel">
          <div className="panel-head"><h3>이벤트 유형별 요청</h3><span className="muted">최근 24시간</span></div>
          {summary && Object.keys(summary.byType).length === 0 && <p className="muted">아직 기록된 활동이 없습니다.</p>}
          <ul className="bars">
            {summary && Object.entries(summary.byType).sort((a, b) => Number(b[1]) - Number(a[1])).map(([type, count]) => (
              <li key={type}>
                <span className="bar-label">{TYPE_LABEL[type as ActivityType] ?? type}</span>
                <span className="bar-track"><span className={`bar-fill ${type === 'ERROR' ? 'bad' : ''}`} style={{ width: `${(Number(count) / max) * 100}%` }} /></span>
                <span className="bar-value">{count}</span>
              </li>
            ))}
          </ul>
        </section>

        <section className="panel">
          <div className="panel-head"><h3>최근 활동</h3><Link to="/admin/activity" className="link">전체 보기 →</Link></div>
          {recent?.length === 0 && <p className="muted">아직 기록된 활동이 없습니다.</p>}
          <ul className="feed">
            {recent?.map(l => (
              <li key={l.id}>
                <span className={`code ${resultClass(l.statusCode)}`}>{l.statusCode}</span>
                <span className="feed-who">{l.userName ?? '비회원'}</span>
                <span className="feed-what">{describe(l)}</span>
                <time>{new Date(l.createdAt).toLocaleTimeString('ko-KR')}</time>
              </li>
            ))}
          </ul>
        </section>
      </div>

      <section className="panel">
        <h3>관리자 메뉴 안내</h3>
        <div className="guide-cards">
          {ADMIN_SECTIONS.slice(1).map(s => (
            <Link key={s.to} to={s.to} className="guide-card">
              <strong>{s.title} →</strong>
              <span>{s.desc}</span>
            </Link>
          ))}
        </div>
      </section>
    </div>
  )
}
