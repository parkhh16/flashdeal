#!/usr/bin/env node
/**
 * 선착순 주문 폭주 부하 테스트 (의존성 없음, Node 18+)
 *
 *   node loadtest/order-rush.mjs                  # 서버의 현재 전략으로 1회
 *   node loadtest/order-rush.mjs --all            # 4개 전략 전부 비교
 *   node loadtest/order-rush.mjs --requests 3000 --concurrency 200 --stock 100
 *
 * --all / --strategy 는 서버를 재시작하지 않고 재고 전략을 바꾸는 개발 도구 API를 쓴다.
 * 서버를 FLASHDEAL_DEV_TOOLS_ENABLED=true 로 띄워야 한다 (기본값은 꺼짐, 운영에서는 켜지 않는다).
 *
 * 흐름: 관리자 로그인 → 사용자 N명 가입 → (전략마다) 전략 전환 → 전용 테스트 상품 생성 → 동시 주문 폭주 → 정합성 검증 → 테스트 상품 판매 중지
 *
 * 전략마다 새 상품을 만드는 이유: 시연용 상품(한정판 키보드 등)의 재고를 덮어쓰며 테스트하면
 * 결제 대기 주문이 만료될 때 재고가 부풀고, 판매량·특가 기간이 오염된다 (실제로 재고 16,602개까지 부풀었음).
 */
const args = Object.fromEntries(process.argv.slice(2).reduce((acc, cur, i, arr) => {
  if (cur.startsWith('--')) acc.push([cur.slice(2), arr[i + 1] && !arr[i + 1].startsWith('--') ? arr[i + 1] : true])
  return acc
}, []))

const BASE = args.base ?? 'http://localhost:8080'
const REQUESTS = Number(args.requests ?? 2000)
const CONCURRENCY = Number(args.concurrency ?? 100)
const STOCK = Number(args.stock ?? 100)
const USERS = Number(args.users ?? 200)
const STRATEGIES = args.all ? ['NAIVE', 'PESSIMISTIC', 'OPTIMISTIC', 'ATOMIC_UPDATE'] : [args.strategy ?? null]

async function call(method, path, { token, body, headers = {} } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers },
    body: body ? JSON.stringify(body) : undefined,
  })
  const text = await res.text()
  return { status: res.status, data: text ? JSON.parse(text) : null }
}

async function pool(tasks, limit) {
  const results = new Array(tasks.length)
  let next = 0
  await Promise.all(Array.from({ length: limit }, async () => {
    while (next < tasks.length) {
      const i = next++
      results[i] = await tasks[i]()
    }
  }))
  return results
}

const pct = (sorted, p) => sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))]

async function prepareUsers() {
  const runId = Date.now().toString(36)
  const tokens = await pool(Array.from({ length: USERS }, (_, i) => async () => {
    const r = await call('POST', '/api/auth/signup', {
      body: { loginId: `load_${runId}_${i}`.slice(0, 20), email: `load-${runId}-${i}@test.com`, password: 'password123!', name: `load${i}` },
    })
    return r.data.accessToken
  }), 20)
  return tokens
}

/** 특가·1인 제한이 없는 부하 테스트 전용 상품. 측정이 끝나면 판매 중지해서 쇼핑몰에 남지 않게 한다 */
async function createProduct(adminToken, label) {
  const r = await call('POST', '/api/admin/products', {
    token: adminToken,
    body: {
      name: `[부하 테스트] ${label} ${new Date().toLocaleString('ko-KR')}`.slice(0, 100),
      subCategory: 'ACCESSORY_ETC', price: 1000, originalPrice: null, stock: STOCK,
      description: '부하 테스트 전용 상품 (측정 후 자동으로 판매 중지)', detail: '',
      dealStartAt: null, dealEndAt: null, dealQuantity: null, perUserLimit: null,
    },
  })
  if (r.status !== 201) throw new Error('테스트 상품을 만들지 못했습니다: ' + JSON.stringify(r.data))
  return r.data.id
}

async function run(adminToken, tokens, strategy) {
  if (strategy) await call('PUT', `/api/admin/stock-strategy/${strategy}`, { token: adminToken })
  const current = devTools
    ? (await call('GET', '/api/admin/stock-strategy', { token: adminToken })).data.strategy
    : '서버 설정값'
  const PRODUCT_ID = await createProduct(adminToken, current)

  const latencies = []
  const statuses = {}
  const started = performance.now()
  await pool(Array.from({ length: REQUESTS }, (_, i) => async () => {
    const t0 = performance.now()
    const r = await call('POST', '/api/orders', {
      token: tokens[i % tokens.length],
      body: { items: [{ productId: PRODUCT_ID, quantity: 1 }] },
      headers: { 'Idempotency-Key': crypto.randomUUID() },
    }).catch(() => ({ status: 0 }))
    latencies.push(performance.now() - t0)
    statuses[r.status] = (statuses[r.status] ?? 0) + 1
  }), CONCURRENCY)
  const elapsed = (performance.now() - started) / 1000

  const remaining = (await call('GET', `/api/products/${PRODUCT_ID}`)).data.stock
  await call('PATCH', `/api/admin/products/${PRODUCT_ID}/active`, { token: adminToken, body: { active: false } })
  const sold = statuses[201] ?? 0
  latencies.sort((a, b) => a - b)
  return {
    strategy: current,
    tps: Math.round(REQUESTS / elapsed),
    p50: Math.round(pct(latencies, 0.5)),
    p95: Math.round(pct(latencies, 0.95)),
    p99: Math.round(pct(latencies, 0.99)),
    sold,
    remaining,
    oversold: sold + remaining - STOCK,
    statuses: JSON.stringify(statuses),
  }
}

const admin = await call('POST', '/api/auth/login', { body: { loginId: args.adminId ?? 'admin', password: args.adminPassword ?? 'admin' } })
/** 메시지를 출력하고 종료한다. 열린 소켓이 정리될 시간을 줘서 Windows의 libuv 종료 경고를 피한다 */
async function die(...lines) {
  lines.forEach(l => console.error(l))
  await new Promise(r => setTimeout(r, 100))
  process.exit(1)
}

if (admin.status !== 200) {
  await die('관리자 로그인 실패. 서버가 떠 있는지 확인하세요: ' + BASE)
}
const devTools = (await call('GET', '/api/admin/stock-strategy', { token: admin.data.accessToken })).status === 200
if (!devTools && STRATEGIES.some(Boolean)) {
  await die('재고 전략을 바꾸는 개발 도구 API가 꺼져 있습니다 (--all / --strategy 사용 불가).',
    '서버를 FLASHDEAL_DEV_TOOLS_ENABLED=true 로 다시 띄우거나, 옵션 없이 서버의 현재 전략으로 실행하세요.')
}
console.log(`사용자 ${USERS}명 준비 중...`)
const tokens = await prepareUsers()

// JIT 워밍업: 첫 측정이 불리하지 않도록 가볍게 한 번 돌린다
await run(admin.data.accessToken, tokens, STRATEGIES[0])

console.log(`\n재고 ${STOCK} / 요청 ${REQUESTS} / 동시성 ${CONCURRENCY}\n`)
const rows = []
for (const s of STRATEGIES) rows.push(await run(admin.data.accessToken, tokens, s))
console.table(rows)

if (devTools) await call('PUT', '/api/admin/stock-strategy/ATOMIC_UPDATE', { token: admin.data.accessToken })
