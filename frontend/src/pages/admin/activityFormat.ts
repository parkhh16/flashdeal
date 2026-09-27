import type { ActivityLog } from '../../api'
import { won } from '../../format'

const str = (v: unknown) => (v === undefined || v === null ? '' : String(v))

/** 로그 한 줄을 사람이 읽을 수 있는 문장으로 */
export function describe(log: ActivityLog): string {
  const d = log.detail ?? {}
  const failed = log.statusCode >= 400
  const error = d.error ? ` · ${str(d.error)}` : ''
  switch (log.eventType) {
    case 'LOGIN': return failed ? `로그인 실패 (${str(d.email)})` : `로그인 (${str(d.email)})`
    case 'SIGNUP': return failed ? `회원가입 실패${error}` : '회원가입'
    case 'SEARCH': return `"${str(d.q)}" 검색${d.results !== undefined ? ` · 결과 ${str(d.results)}건` : ''}${error}`
    case 'PRODUCT_VIEW': return `상품 #${str(d.productId)} 조회${error}`
    case 'ORDER_CREATE': return failed
      ? `주문 실패 (상품 ${(d.items as string[] | undefined)?.join(', ') ?? ''})${error}`
      : `주문 ${str(d.orderNo)} · ${d.amount ? won(Number(d.amount)) : ''}`
    case 'IDEMPOTENT_REPLAY': return `중복 요청 감지 → 첫 응답을 재사용 (${str(d.orderNo || `주문 #${str(d.orderId)}`)})`
    case 'PAYMENT': return failed ? `주문 #${str(d.orderId)} 결제 실패${error}` : `주문 #${str(d.orderId)} 결제 · ${str(d.paymentStatus)}`
    case 'ORDER_VIEW': return `주문 상세 조회 ${log.target.replace('/api/orders/', '#')}${error}`
    case 'ADMIN_ACTION': return `${log.method} ${log.target.replace('/api/admin/', '')}${error}`
    case 'ERROR': return `서버 오류 · ${log.method} ${log.target}${error}`
    default: return `${log.method} ${log.target}`
  }
}

export const resultClass = (status: number) => (status >= 500 ? 'c5' : status >= 400 ? 'c4' : 'c2')

/** 서버는 LocalDateTime(오프셋 없음)을 받으므로 로컬 시각 문자열로 보낸다 */
export function localIso(d: Date) {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

export const PERIODS = [
  { key: '1h', label: '최근 1시간', ms: 3600_000 },
  { key: '24h', label: '최근 24시간', ms: 86_400_000 },
  { key: '7d', label: '최근 7일', ms: 7 * 86_400_000 },
  { key: 'all', label: '전체 기간', ms: 0 },
]
