// 화면 검증용 상태 만들기: 관리자 재고 조정 API(증감)로 몇 개 상품을 "N개 남음"과 품절 상태로 만든다.
// 시드 데이터는 최소 재고가 23이라 핵심 표시(남은 수량, 품절)가 스크린샷에 안 나온다. 백엔드 코드는 건드리지 않는다.
import { BASE, ACCOUNTS } from './lib.mjs'

const post = (path, body, token) => fetch(`${BASE}${path}`, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(token && { Authorization: `Bearer ${token}` }) }, body: JSON.stringify(body) })
const login = await (await post('/api/auth/login', { loginId: ACCOUNTS.admin.id, password: ACCOUNTS.admin.pw })).json()
const token = login.accessToken
const list = await (await fetch(`${BASE}/api/admin/products`, { headers: { Authorization: `Bearer ${token}` } })).json()
const byId = new Map(list.map(p => [p.id, p]))

// 상품 id -> 목표 재고 (1번은 건드리지 않는다: 기본 상세 화면용)
const TARGET = { 2: 4, 3: 9, 4: 0, 5: 10 }
for (const [id, target] of Object.entries(TARGET)) {
  const p = byId.get(Number(id)); if (!p) continue
  const delta = target - p.stock
  if (delta === 0) { console.log(`#${id} ${p.name}: 이미 ${target}`); continue }
  const r = await post(`/api/admin/products/${id}/stock-adjustments`, { delta, reason: '화면 검증용 재고 조정' }, token)
  console.log(`#${id} ${p.name}: ${p.stock} -> ${target} (${r.status})`)
}
