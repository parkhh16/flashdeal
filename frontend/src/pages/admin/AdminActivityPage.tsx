import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { adminApi, type ActivityFilter, type ActivityLog, type ActivitySummary, type ActivityType, type StatusClass, type UserOption } from '../../api'
import { useApp } from '../../context/AppContext'
import { StatCards } from './AdminDashboard'
import { describe, localIso, PERIODS, resultClass } from './activityFormat'

const TYPES: { value: ActivityType; label: string }[] = [
  { value: 'LOGIN', label: '로그인' }, { value: 'SEARCH', label: '검색' }, { value: 'PRODUCT_VIEW', label: '상품 조회' },
  { value: 'ORDER_CREATE', label: '주문' }, { value: 'IDEMPOTENT_REPLAY', label: '멱등성 재생' }, { value: 'PAYMENT', label: '결제' },
  { value: 'ADMIN_ACTION', label: '관리자 작업' }, { value: 'ERROR', label: '서버 에러' },
]
const STATUSES: { value: StatusClass; label: string }[] = [
  { value: 'SUCCESS', label: '성공 (2xx)' }, { value: 'CLIENT_ERROR', label: '요청 오류 (4xx)' }, { value: 'SERVER_ERROR', label: '서버 오류 (5xx)' },
]

export function AdminActivityPage() {
  const { fail } = useApp()
  const [users, setUsers] = useState<UserOption[]>([])
  const [searchParams] = useSearchParams()
  const initialUser = Number(searchParams.get('userId')) || undefined
  const [userId, setUserId] = useState<number | undefined>(initialUser)
  const [period, setPeriod] = useState('24h')
  const [type, setType] = useState<ActivityType | undefined>()
  const [status, setStatus] = useState<StatusClass | undefined>()
  const [logs, setLogs] = useState<ActivityLog[] | null>(null)
  const [page, setPage] = useState(0)
  const [hasMore, setHasMore] = useState(false)
  const [summary, setSummary] = useState<ActivitySummary | null>(null)
  const [view, setView] = useState<'table' | 'timeline'>('table')

  useEffect(() => { adminApi.users().then(r => setUsers(r.data)).catch(fail) }, [])
  useEffect(() => { setView(userId ? 'timeline' : 'table') }, [userId])

  const filter = useMemo<ActivityFilter>(() => {
    const ms = PERIODS.find(p => p.key === period)?.ms ?? 0
    return { userId, type, status, from: ms ? localIso(new Date(Date.now() - ms)) : undefined }
  }, [userId, period, type, status])

  const load = (p: number) => {
    adminApi.activity(filter, p, 30).then(r => {
      setLogs(prev => (p === 0 ? r.data.content : [...(prev ?? []), ...r.data.content]))
      setHasMore(r.data.page + 1 < r.data.totalPages)
      setPage(p)
    }).catch(fail)
  }

  useEffect(() => {
    setLogs(null)
    load(0)
    adminApi.activitySummary(filter).then(r => setSummary(r.data)).catch(fail)
  }, [filter])

  const selectedUser = users.find(u => u.id === userId)
  const byDate = useMemo(() => {
    const groups = new Map<string, ActivityLog[]>()
    logs?.forEach(l => {
      const key = new Date(l.createdAt).toLocaleDateString('ko-KR', { month: 'long', day: 'numeric', weekday: 'short' })
      groups.set(key, [...(groups.get(key) ?? []), l])
    })
    return [...groups.entries()]
  }, [logs])

  return (
    <div className="admin-body">
      <div className="filter-bar">
        <label>사용자
          <select value={userId ?? ''} onChange={e => setUserId(e.target.value ? Number(e.target.value) : undefined)}>
            <option value="">전체 사용자</option>
            {users.map(u => <option key={u.id} value={u.id}>{u.name} ({u.loginId ?? '탈퇴'}){u.role === 'ADMIN' ? ' · 관리자' : ''}</option>)}
          </select>
        </label>
        <label>기간
          <select value={period} onChange={e => setPeriod(e.target.value)}>
            {PERIODS.map(p => <option key={p.key} value={p.key}>{p.label}</option>)}
          </select>
        </label>
        <label>이벤트
          <select value={type ?? ''} onChange={e => setType((e.target.value || undefined) as ActivityType | undefined)}>
            <option value="">전체</option>
            {TYPES.map(t => <option key={t.value} value={t.value}>{t.label}</option>)}
          </select>
        </label>
        <label>결과
          <select value={status ?? ''} onChange={e => setStatus((e.target.value || undefined) as StatusClass | undefined)}>
            <option value="">전체</option>
            {STATUSES.map(s => <option key={s.value} value={s.value}>{s.label}</option>)}
          </select>
        </label>
        <div className="view-toggle" role="group" aria-label="보기 방식">
          <button className={view === 'table' ? 'active' : ''} onClick={() => setView('table')}>표</button>
          <button className={view === 'timeline' ? 'active' : ''} onClick={() => setView('timeline')}>타임라인</button>
        </div>
      </div>

      <StatCards summary={summary} />

      {selectedUser && <p className="muted">{selectedUser.name}님의 활동 {summary?.totalRequests ?? 0}건</p>}
      {logs === null && <p className="muted">불러오는 중…</p>}
      {logs?.length === 0 && <div className="empty small"><p>조건에 맞는 활동이 없습니다.</p></div>}

      {logs && logs.length > 0 && view === 'table' && (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr><th>시각</th><th>사용자</th><th>이벤트</th><th>내용</th><th>결과</th><th>응답</th><th>traceId</th></tr>
            </thead>
            <tbody>
              {logs.map(l => (
                <tr key={l.id}>
                  <td className="nowrap">{new Date(l.createdAt).toLocaleString('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' })}</td>
                  <td className="nowrap">{l.userName ?? <span className="muted">비회원</span>}</td>
                  <td className="nowrap"><span className={`type-tag t-${l.eventType.toLowerCase()}`}>{l.eventLabel}</span></td>
                  <td>{describe(l)}</td>
                  <td><span className={`code ${resultClass(l.statusCode)}`}>{l.statusCode}</span></td>
                  <td className="num">{l.latencyMs}ms</td>
                  <td><code className="muted">{l.requestId}</code></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {logs && logs.length > 0 && view === 'timeline' && (
        <div className="timeline">
          {byDate.map(([date, items]) => (
            <section key={date}>
              <h4>{date}</h4>
              <ol>
                {items.map(l => (
                  <li key={l.id} className={l.statusCode >= 400 ? 'fail' : ''}>
                    <time>{new Date(l.createdAt).toLocaleTimeString('ko-KR')}</time>
                    <div>
                      <div className="tl-main">
                        {!userId && <strong>{l.userName ?? '비회원'} · </strong>}{describe(l)}
                      </div>
                      <div className="muted">{l.eventLabel} · {l.statusCode} · {l.latencyMs}ms · {l.requestId}</div>
                    </div>
                  </li>
                ))}
              </ol>
            </section>
          ))}
        </div>
      )}

      {hasMore && <button className="ghost load-more" onClick={() => load(page + 1)}>더 보기</button>}
    </div>
  )
}
