import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { adminApi, type UserOption, type UserStatus } from '../../api'
import { useApp } from '../../context/AppContext'

const STATUS_LABEL: Record<UserStatus, string> = { ACTIVE: '정상', SUSPENDED: '정지', WITHDRAWN: '탈퇴' }

export function AdminUsersPage() {
  const { toast, fail } = useApp()
  const [users, setUsers] = useState<UserOption[] | null>(null)
  const [q, setQ] = useState('')
  const [status, setStatus] = useState<UserStatus | ''>('')
  const [busy, setBusy] = useState<number | null>(null)

  const load = () => adminApi.users(q || undefined, status || undefined).then(r => setUsers(r.data)).catch(fail)
  useEffect(() => { const t = setTimeout(load, 250); return () => clearTimeout(t) }, [q, status])

  const act = async (u: UserOption, action: () => Promise<unknown>, done: string, confirmMsg?: string) => {
    if (confirmMsg && !window.confirm(confirmMsg)) return
    setBusy(u.id)
    try {
      await action()
      toast(done, 'success')
      load()
    } catch (e) { fail(e) } finally { setBusy(null) }
  }

  const counts = users?.reduce((acc, u) => ({ ...acc, [u.status]: (acc[u.status] ?? 0) + 1 }), {} as Record<string, number>)

  return (
    <div className="admin-body">
      <div className="filter-bar">
        <label>검색<input placeholder="아이디, 이름, 이메일" value={q} onChange={e => setQ(e.target.value)} /></label>
        <label>상태
          <select value={status} onChange={e => setStatus(e.target.value as UserStatus | '')}>
            <option value="">전체</option>
            {(Object.keys(STATUS_LABEL) as UserStatus[]).map(s => <option key={s} value={s}>{STATUS_LABEL[s]}</option>)}
          </select>
        </label>
        {counts && <span className="muted">정상 {counts.ACTIVE ?? 0} · 정지 {counts.SUSPENDED ?? 0} · 탈퇴 {counts.WITHDRAWN ?? 0}</span>}
      </div>

      {users?.length === 0 && <div className="empty small"><p>조건에 맞는 회원이 없습니다.</p></div>}
      {users && users.length > 0 && (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr><th>회원</th><th>이메일</th><th>상태</th><th>주문</th><th>가입일</th><th>최근 로그인</th><th>관리</th></tr>
            </thead>
            <tbody>
              {users.map(u => {
                const manageable = u.role !== 'ADMIN' && u.status !== 'WITHDRAWN'
                return (
                  <tr key={u.id} className={u.status === 'WITHDRAWN' ? 'inactive' : ''}>
                    <td className="nowrap">
                      <div className="cell-title">{u.name} {u.role === 'ADMIN' && <span className="role admin">ADMIN</span>}</div>
                      <div className="muted">{u.loginId ?? '(탈퇴)'} · #{u.id}</div>
                    </td>
                    <td>{u.email}</td>
                    <td className="nowrap">
                      <span className={`user-status u-${u.status.toLowerCase()}`}>{STATUS_LABEL[u.status]}</span>
                      {u.locked && <span className="user-status u-locked" title="로그인 5회 실패로 잠김">잠김</span>}
                      {!u.locked && u.failedLoginCount > 0 && <div className="muted">로그인 실패 {u.failedLoginCount}회</div>}
                    </td>
                    <td className="num">{u.orderCount}</td>
                    <td className="nowrap">{new Date(u.createdAt).toLocaleDateString('ko-KR')}</td>
                    <td className="nowrap">{u.lastLoginAt ? new Date(u.lastLoginAt).toLocaleString('ko-KR') : <span className="muted">-</span>}</td>
                    <td>
                      <div className="row-actions">
                        <Link to={`/admin/activity?userId=${u.id}`} className="ghost-link small">활동 로그</Link>
                        {manageable && u.status === 'ACTIVE' && (
                          <button className="ghost small danger" disabled={busy === u.id}
                                  onClick={() => act(u, () => adminApi.setUserStatus(u.id, 'SUSPENDED'), `${u.name}님을 정지했습니다.`,
                                    `${u.name}(${u.loginId})님을 정지할까요?\n로그인 중인 세션도 즉시 끊기고, 다시 로그인할 수 없습니다.`)}>정지</button>
                        )}
                        {manageable && u.status === 'SUSPENDED' && (
                          <button className="ghost small" disabled={busy === u.id}
                                  onClick={() => act(u, () => adminApi.setUserStatus(u.id, 'ACTIVE'), `${u.name}님의 정지를 해제했습니다.`)}>정지 해제</button>
                        )}
                        {manageable && u.status === 'ACTIVE' && (
                          <button className="ghost small" disabled={busy === u.id}
                                  onClick={() => act(u, () => adminApi.forceLogout(u.id), `${u.name}님을 모든 기기에서 로그아웃시켰습니다.`,
                                    `${u.name}님을 모든 기기에서 로그아웃시킬까요?`)}>강제 로그아웃</button>
                        )}
                        {manageable && u.locked && (
                          <button className="ghost small" disabled={busy === u.id}
                                  onClick={() => act(u, () => adminApi.unlockUser(u.id), `${u.name}님의 로그인 잠금을 풀었습니다.`)}>잠금 해제</button>
                        )}
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
