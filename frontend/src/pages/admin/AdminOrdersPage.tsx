import { useEffect, useState } from 'react'
import { adminApi, type AdminOrder, type OrderStatus, type UserOption } from '../../api'
import { useApp } from '../../context/AppContext'
import { won } from '../../format'

const STATUS: { value: OrderStatus; label: string }[] = [
  { value: 'PENDING_PAYMENT', label: '결제 대기' }, { value: 'PAYING', label: '결제 확인 중' }, { value: 'PAID', label: '결제 완료' },
  { value: 'EXPIRED', label: '기한 만료' }, { value: 'CANCELLED', label: '취소' },
]
const label = (s: OrderStatus) => STATUS.find(x => x.value === s)?.label ?? s
const PAYMENT_LABEL: Record<string, string> = {
  REQUESTED: '요청됨', APPROVED: '승인', FAILED: '실패', UNKNOWN: '확인 중', CANCELLED: '환불 완료',
}

export function AdminOrdersPage() {
  const { toast, fail } = useApp()
  const [users, setUsers] = useState<UserOption[]>([])
  const [status, setStatus] = useState<OrderStatus | ''>('')
  const [userId, setUserId] = useState<number | undefined>()
  const [orders, setOrders] = useState<AdminOrder[] | null>(null)
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [busy, setBusy] = useState<number | null>(null)

  useEffect(() => { adminApi.users().then(r => setUsers(r.data)).catch(fail) }, [])

  const load = (p = page) => adminApi.orders(status || undefined, userId, p).then(r => {
    setOrders(r.data.content)
    setTotal(r.data.totalElements)
    setTotalPages(r.data.totalPages)
    setPage(p)
  }).catch(fail)

  useEffect(() => { load(0) }, [status, userId])

  const cancel = async (o: AdminOrder) => {
    const refund = o.status === 'PAID'
    const msg = refund
      ? `${o.orderNo} 주문을 취소할까요?\n결제 금액 ${won(o.totalAmount)}을 PG에 환불 요청하고, 성공하면 재고를 복구합니다.`
      : `${o.orderNo} 주문을 취소할까요?\n선점한 재고가 복구됩니다.`
    if (!window.confirm(msg)) return
    setBusy(o.id)
    try {
      await adminApi.cancelOrder(o.id)
      toast(refund ? '환불 후 주문을 취소했습니다.' : '주문을 취소했습니다.', 'success')
      load()
    } catch (e) {
      fail(e)
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="admin-body">
      <div className="filter-bar">
        <label>상태
          <select value={status} onChange={e => setStatus(e.target.value as OrderStatus | '')}>
            <option value="">전체</option>
            {STATUS.map(s => <option key={s.value} value={s.value}>{s.label}</option>)}
          </select>
        </label>
        <label>주문자
          <select value={userId ?? ''} onChange={e => setUserId(e.target.value ? Number(e.target.value) : undefined)}>
            <option value="">전체</option>
            {users.map(u => <option key={u.id} value={u.id}>{u.name} ({u.loginId ?? '탈퇴'})</option>)}
          </select>
        </label>
        <span className="muted">총 {total}건</span>
      </div>

      {orders?.length === 0 && <div className="empty small"><p>조건에 맞는 주문이 없습니다.</p></div>}
      {orders && orders.length > 0 && (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr><th>주문</th><th>주문자</th><th>상품</th><th>금액</th><th>주문 상태</th><th>결제</th><th></th></tr>
            </thead>
            <tbody>
              {orders.map(o => (
                <tr key={o.id}>
                  <td className="nowrap"><div className="cell-title">{o.orderNo}</div><div className="muted">{new Date(o.createdAt).toLocaleString('ko-KR')}</div></td>
                  <td className="nowrap">{o.userName ?? `#${o.userId}`}</td>
                  <td>{o.items.map(i => `${i.productName} × ${i.quantity}`).join(', ')}</td>
                  <td className="nowrap num">{won(o.totalAmount)}</td>
                  <td><span className={`status s-${o.status.toLowerCase()}`}>{label(o.status)}</span></td>
                  <td className="nowrap">{o.paymentStatus ? PAYMENT_LABEL[o.paymentStatus] ?? o.paymentStatus : <span className="muted">–</span>}</td>
                  <td>
                    {(o.status === 'PENDING_PAYMENT' || o.status === 'PAID') && (
                      <button className="ghost small danger" onClick={() => cancel(o)} disabled={busy === o.id}>
                        {busy === o.id ? '처리 중…' : o.status === 'PAID' ? '환불·취소' : '취소'}
                      </button>
                    )}
                    {o.status === 'PAYING' && <span className="muted" title="결제 결과를 모르는 상태라 취소할 수 없습니다. 테스트 실험실에서 결제 대사를 실행하세요.">대사 대기</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {totalPages > 1 && (
        <div className="pager">
          <button className="ghost small" disabled={page === 0} onClick={() => load(page - 1)}>이전</button>
          <span className="muted">{page + 1} / {totalPages}</span>
          <button className="ghost small" disabled={page + 1 >= totalPages} onClick={() => load(page + 1)}>다음</button>
        </div>
      )}
    </div>
  )
}
