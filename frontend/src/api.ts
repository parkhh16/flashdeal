export type Category = 'KEYBOARD' | 'MOUSE' | 'MONITOR' | 'AUDIO' | 'ACCESSORY'
export type OrderStatus = 'PENDING_PAYMENT' | 'PAYING' | 'PAID' | 'EXPIRED' | 'CANCELLED'
export type StockStrategy = 'NAIVE' | 'PESSIMISTIC' | 'OPTIMISTIC' | 'ATOMIC_UPDATE'
export type DealStatus = 'NONE' | 'UPCOMING' | 'ONGOING' | 'SOLD_OUT' | 'ENDED'
export type SortKey = 'POPULAR' | 'PRICE_ASC' | 'PRICE_DESC' | 'DEADLINE'

export interface Session { accessToken: string; userId: number; loginId: string; name: string; role: 'USER' | 'ADMIN' }
export interface Me { id: number; loginId: string; name: string; email: string; role: string; createdAt: string; lastLoginAt: string | null }
export interface Product {
  id: number; name: string
  category: Category; categoryLabel: string; subCategory: string; subCategoryLabel: string
  price: number; originalPrice: number | null; discountRate: number
  stock: number; soldCount: number; description: string; thumbnail: string | null
  dealStatus: DealStatus; dealStartAt: string | null; dealEndAt: string | null
  dealQuantity: number | null; perUserLimit: number | null
}
export interface ProductDetail extends Product {
  detail: string | null; specs: Record<string, string>; images: string[]; related: Product[]
}
export interface CategoryNode { code: string; label: string; count: number; children: CategoryNode[] }
export interface CategoryTree { total: number; deals: number; categories: CategoryNode[] }
export interface OrderItem { productId: number; productName: string; unitPrice: number; quantity: number }
export interface Order {
  id: number; orderNo: string; status: OrderStatus; totalAmount: number
  expiresAt: string; createdAt: string; items: OrderItem[]
}
export interface Page<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
export interface SearchFilter { keyword: string | null; category: Category | null; minPrice: number | null; maxPrice: number | null }
export interface AiSearchResult {
  query: string; parsedBy: 'LLM' | 'LLM_CACHED' | 'RULE_FALLBACK'; filter: SearchFilter
  fallbackReason: string | null; parseMillis: number; products: Product[]; originalQuery: string | null
}
export interface PaymentResult { orderId: number; paymentKey: string; status: string; message: string }
export interface Chaos { latencyMs: number; failureRate: number; declineRate: number; timeoutRate: number; failAfterPgApproval: boolean }
export interface Mismatch { orderId: number; orderNo: string; orderStatus: string; orderAmount: number; approvedAmount: number }

export interface ProductQuery {
  category?: string; sub?: string; deal?: boolean; minPrice?: number; maxPrice?: number
  excludeSoldOut?: boolean; sort?: SortKey; keyword?: string
}

export class ApiError extends Error {
  status: number
  code: string
  traceId?: string

  constructor(status: number, code: string, message: string, traceId?: string) {
    super(message)
    this.status = status
    this.code = code
    this.traceId = traceId
  }
}

export interface ApiResponse<T> { status: number; data: T; replayed: boolean }

let token: string | null = null
export const setToken = (t: string | null) => { token = t }

/** 토큰이 무효화된 경우(탈퇴·강제 로그아웃·정지·만료) 앱 전체에서 한 번에 처리하기 위한 훅 */
let onSessionInvalid: ((message: string) => void) | null = null
export const setSessionInvalidHandler = (h: ((message: string) => void) | null) => { onSessionInvalid = h }
const SESSION_CODES = ['A004', 'A005', 'C002']

export async function request<T>(method: string, url: string, body?: unknown, headers: Record<string, string> = {}): Promise<ApiResponse<T>> {
  let res: Response
  try {
    res = await fetch(url, {
      method,
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...headers,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiError(0, 'NETWORK', '서버에 연결할 수 없습니다. 잠시 후 다시 시도해주세요.')
  }
  const text = await res.text()
  let data = null
  try { data = text ? JSON.parse(text) : null } catch { /* 본문이 JSON이 아닌 경우 */ }
  if (!res.ok) {
    if (token && SESSION_CODES.includes(data?.code) && onSessionInvalid) onSessionInvalid(data?.message ?? '다시 로그인해주세요.')
    throw new ApiError(res.status, data?.code ?? String(res.status), data?.message ?? '요청을 처리하지 못했습니다.', data?.traceId)
  }
  return { status: res.status, data, replayed: res.headers.get('Idempotent-Replayed') === 'true' }
}

const qs = (params: Record<string, unknown>) => {
  const s = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== '' && v !== false) s.set(k, String(v)) })
  const str = s.toString()
  return str ? `?${str}` : ''
}

export const api = {
  login: (loginId: string, password: string) => request<Session>('POST', '/api/auth/login', { loginId, password }),
  signup: (body: { loginId: string; password: string; name: string; email: string }) => request<Session>('POST', '/api/auth/signup', body),
  checkLoginId: (value: string) => request<{ available: boolean }>('GET', `/api/auth/check-login-id?value=${encodeURIComponent(value)}`),
  me: () => request<Me>('GET', '/api/me'),
  logoutAll: () => request('POST', '/api/me/logout-all'),
  withdraw: (password: string) => request('DELETE', '/api/me', { password }),
  categories: () => request<CategoryTree>('GET', '/api/categories'),
  products: (q: ProductQuery = {}) => request<Product[]>('GET', `/api/products${qs({ ...q })}`),
  product: (id: number) => request<ProductDetail>('GET', `/api/products/${id}`),
  aiSearch: (q: string, correct = true) =>
    request<AiSearchResult>('GET', `/api/products/search?q=${encodeURIComponent(q)}${correct ? '' : '&correct=false'}`),
  createOrder: (productId: number, quantity: number, idempotencyKey: string) =>
    request<Order>('POST', '/api/orders', { items: [{ productId, quantity }] }, { 'Idempotency-Key': idempotencyKey }),
  myOrders: () => request<Page<Order>>('GET', '/api/orders?size=20'),
  pay: (orderId: number, idempotencyKey: string) =>
    request<PaymentResult>('POST', `/api/orders/${orderId}/payment`, undefined, { 'Idempotency-Key': idempotencyKey }),
  admin: {
    strategy: () => request<{ strategy: StockStrategy }>('GET', '/api/admin/stock-strategy'),
    setStrategy: (s: StockStrategy) => request<{ strategy: StockStrategy }>('PUT', `/api/admin/stock-strategy/${s}`),
    chaos: () => request<Chaos>('GET', '/api/admin/chaos'),
    setChaos: (c: Chaos) => request<Chaos>('PUT', '/api/admin/chaos', c),
    reconcile: () => request<{ checked: number; approved: number; failed: number; stillUnknown: number }>('POST', '/api/admin/reconcile'),
    expire: () => request<{ expired: number }>('POST', '/api/admin/expire'),
    consistency: () => request<Mismatch[]>('GET', '/api/admin/consistency-report'),
  },
}

// ── 활동 로그 / 관리자 ──
export type ActivityType = 'LOGIN' | 'SIGNUP' | 'SEARCH' | 'PRODUCT_VIEW' | 'ORDER_CREATE' | 'IDEMPOTENT_REPLAY'
  | 'PAYMENT' | 'ORDER_VIEW' | 'ADMIN_ACTION' | 'ERROR'
export type StatusClass = 'SUCCESS' | 'CLIENT_ERROR' | 'SERVER_ERROR'
export interface ActivityLog {
  id: number; userId: number | null; userName: string | null; eventType: ActivityType; eventLabel: string
  method: string; target: string; statusCode: number; latencyMs: number; requestId: string | null
  detail: Record<string, unknown>; createdAt: string
}
export interface ActivitySummary {
  totalRequests: number; orders: number; clientErrors: number; serverErrors: number
  errorRate: number; avgLatencyMs: number; byType: Partial<Record<ActivityType, number>>; droppedLogs: number
}
export interface ActivityFilter { userId?: number; type?: ActivityType; status?: StatusClass; from?: string; to?: string }
export type UserStatus = 'ACTIVE' | 'SUSPENDED' | 'WITHDRAWN'
export interface UserOption {
  id: number; loginId: string | null; name: string; email: string; role: 'USER' | 'ADMIN'; status: UserStatus
  locked: boolean; failedLoginCount: number; lastLoginAt: string | null; createdAt: string; withdrawnAt: string | null; orderCount: number
}
export interface MyActivity { recentSearches: string[]; recentlyViewed: Product[] }
/** stock = 지금 팔 수 있는 가용 재고, held = 결제 대기·결제 확인 중 주문이 쥐고 있는 수량 */
export interface AdminProduct extends Product { active: boolean; detail: string | null; held: number }
export interface StockAdjustResult { productId: number; delta: number; stock: number; held: number }
export interface ProductForm {
  name: string; subCategory: string; price: number; originalPrice: number | null; stock: number | null
  description: string; detail: string; dealStartAt: string | null; dealEndAt: string | null
  dealQuantity: number | null; perUserLimit: number | null
}
export interface AdminOrder extends Order { userId: number; userName: string | null; paymentStatus: string | null }
export interface ConcurrencyResult {
  strategy: StockStrategy; initialStock: number; requests: number; success: number; outOfStock: number
  conflicts: number; errors: number; finalStock: number; oversold: number; consistent: boolean
  elapsedMs: number; throughput: number
}

export const adminApi = {
  activity: (f: ActivityFilter, page = 0, size = 30) =>
    request<Page<ActivityLog>>('GET', `/api/admin/activity${qs({ ...f, page, size })}`),
  activitySummary: (f: ActivityFilter) => request<ActivitySummary>('GET', `/api/admin/activity/summary${qs({ ...f })}`),
  users: (q?: string, status?: UserStatus) => request<UserOption[]>('GET', `/api/admin/users${qs({ q, status })}`),
  setUserStatus: (id: number, status: UserStatus) => request('PATCH', `/api/admin/users/${id}/status`, { status }),
  forceLogout: (id: number) => request('POST', `/api/admin/users/${id}/logout`),
  unlockUser: (id: number) => request('POST', `/api/admin/users/${id}/unlock`),
  products: () => request<AdminProduct[]>('GET', '/api/admin/products'),
  createProduct: (f: ProductForm) => request<AdminProduct>('POST', '/api/admin/products', f),
  updateProduct: (id: number, f: ProductForm) => request<AdminProduct>('PUT', `/api/admin/products/${id}`, f),
  setActive: (id: number, active: boolean) => request<AdminProduct>('PATCH', `/api/admin/products/${id}/active`, { active }),
  /** 재고는 덮어쓰지 않고 증감한다 (입고 +N / 출고 -N) */
  adjustStock: (id: number, delta: number, reason: string) =>
    request<StockAdjustResult>('POST', `/api/admin/products/${id}/stock-adjustments`, { delta, reason }),
  orders: (status?: string, userId?: number, page = 0) =>
    request<Page<AdminOrder>>('GET', `/api/admin/orders${qs({ status, userId, page, size: 20 })}`),
  cancelOrder: (id: number) => request<{ status: OrderStatus }>('POST', `/api/admin/orders/${id}/cancel`),
  concurrencyTest: (stock: number, requests: number) =>
    request<ConcurrencyResult>('POST', '/api/admin/tools/concurrency-test', { stock, requests }),
}

export const myActivity = (userId: number) => request<MyActivity>('GET', `/api/users/${userId}/activity`)
