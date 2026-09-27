import { useEffect, useState } from 'react'
import { Link, Navigate } from 'react-router-dom'
import { api, myActivity, type MyActivity, type Order, type OrderStatus } from '../api'
import { ProductCard } from '../components/ProductCard'
import { useApp } from '../context/AppContext'
import { remaining, won } from '../format'
import { useNow } from '../hooks'

const STATUS_LABEL: Record<OrderStatus, string> = {
  PENDING_PAYMENT: '결제 대기',
  PAYING: '결제 확인 중',
  PAID: '결제 완료',
  EXPIRED: '기한 만료',
  CANCELLED: '주문 취소',
}

function Countdown({ until }: { until: string }) {
  const now = useNow()
  return <span className="countdown">결제 기한 {remaining(new Date(until).getTime() - now)}</span>
}

/** 사용자용 "내 활동": 주문, 최근 검색어, 최근 본 상품. 상태코드 같은 개발자 정보는 보여주지 않는다 */
export function MyActivityPage() {
  const { session, fail, toast, ordersVersion } = useApp()
  const [orders, setOrders] = useState<Order[] | null>(null)
  const [activity, setActivity] = useState<MyActivity | null>(null)
  const [paying, setPaying] = useState<number | null>(null)

  const loadOrders = () => api.myOrders().then(r => setOrders(r.data.content)).catch(e => { fail(e); setOrders([]) })

  useEffect(() => {
    if (!session) return
    loadOrders()
    myActivity(session.userId).then(r => setActivity(r.data)).catch(() => setActivity({ recentSearches: [], recentlyViewed: [] }))
  }, [ordersVersion, session])

  // 결제 확인 중(PAYING)인 주문이 있으면 대사 결과를 기다리며 주기적으로 갱신한다
  useEffect(() => {
    if (!orders?.some(o => o.status === 'PAYING')) return
    const t = setInterval(loadOrders, 3000)
    return () => clearInterval(t)
  }, [orders])

  if (!session) return <Navigate to="/" replace />

  const pay = async (o: Order) => {
    setPaying(o.id)
    try {
      const { status, data } = await api.pay(o.id, crypto.randomUUID())
      toast(data.message, status === 200 ? 'success' : 'info')
    } catch (e) {
      fail(e)
    } finally {
      setPaying(null)
      loadOrders()
    }
  }

  return (
    <div className="me-page">
      <div className="shop-head"><h2>내 활동</h2></div>

      <section className="me-section">
        <h3>주문 내역</h3>
        {orders === null && <p className="muted">불러오는 중…</p>}
        {orders?.length === 0 && (
          <div className="empty small"><p>아직 주문한 상품이 없어요.</p><Link to="/?deal=1&sort=DEADLINE" className="ghost-link">오늘의 특가 보러가기</Link></div>
        )}
        <div className="orders">
          {orders?.map(o => (
            <div key={o.id} className="order">
              <div className="order-head">
                <span className={`status s-${o.status.toLowerCase()}`}>{STATUS_LABEL[o.status]}</span>
                <span className="muted">{new Date(o.createdAt).toLocaleString('ko-KR')}</span>
                <span className="muted">주문번호 {o.orderNo}</span>
              </div>
              <ul>
                {o.items.map(i => (
                  <li key={i.productId}>
                    <Link to={`/products/${i.productId}`}>{i.productName}</Link> × {i.quantity} <span className="muted">({won(i.unitPrice)})</span>
                  </li>
                ))}
              </ul>
              <div className="order-foot">
                <strong>{won(o.totalAmount)}</strong>
                {o.status === 'PENDING_PAYMENT' && (
                  <>
                    <Countdown until={o.expiresAt} />
                    <button onClick={() => pay(o)} disabled={paying === o.id}>{paying === o.id ? '결제 중…' : '결제하기'}</button>
                  </>
                )}
                {o.status === 'PAYING' && <span className="muted">결제 결과를 확인하고 있어요. 잠시만 기다려주세요.</span>}
                {o.status === 'EXPIRED' && <span className="muted">결제 기한이 지나 주문이 자동 취소되었어요.</span>}
                {o.status === 'CANCELLED' && <span className="muted">취소된 주문이에요. 결제하셨다면 환불이 진행됩니다.</span>}
              </div>
            </div>
          ))}
        </div>
      </section>

      <section className="me-section">
        <h3>최근 검색어</h3>
        {activity?.recentSearches.length === 0 && <p className="muted">최근 검색한 기록이 없어요.</p>}
        <div className="parsed">
          {activity?.recentSearches.map(q => <Link key={q} to={`/?q=${encodeURIComponent(q)}`} className="chip">{q}</Link>)}
        </div>
      </section>

      <section className="me-section">
        <h3>최근 본 상품</h3>
        {activity?.recentlyViewed.length === 0 && <p className="muted">최근 본 상품이 없어요.</p>}
        <div className="grid">{activity?.recentlyViewed.map(p => <ProductCard key={p.id} product={p} />)}</div>
      </section>
    </div>
  )
}
