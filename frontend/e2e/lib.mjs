// 스크린샷/E2E 공용: 고정 화면 목록, 로그인 헬퍼, 콘솔 에러 수집
export const BASE = process.env.BASE_URL ?? 'http://localhost:5173'

export const ACCOUNTS = {
  user: { id: 'user1', pw: 'user1234!' },
  admin: { id: 'admin', pw: process.env.ADMIN_PASSWORD ?? 'admin' },
}

export const VIEWPORTS = {
  mobile: { width: 390, height: 844 },
  pc: { width: 1280, height: 800 },
}
export const SCHEMES = ['light', 'dark']

// 고정 화면 목록. 라운드가 바뀌어도 이 목록은 바꾸지 않는다 (전/후 비교가 되어야 한다)
export const SCREENS = [
  { name: 'shop', path: '/', role: null },
  { name: 'product', path: '/products/1', role: null },
  { name: 'login', path: '/login', role: null },
  { name: 'signup', path: '/signup', role: null },
  { name: 'me', path: '/me', role: 'user' },
  { name: 'account', path: '/me/account', role: 'user' },
  { name: 'admin', path: '/admin', role: 'admin' },
  { name: 'admin-products', path: '/admin/products', role: 'admin' },
  { name: 'admin-orders', path: '/admin/orders', role: 'admin' },
  { name: 'admin-users', path: '/admin/users', role: 'admin' },
  { name: 'admin-activity', path: '/admin/activity', role: 'admin' },
  { name: 'admin-demand', path: '/admin/demand', role: 'admin' },
]

export async function login(page, who) {
  const { id, pw } = ACCOUNTS[who]
  await page.goto(`${BASE}/login`)
  await page.getByLabel('아이디 또는 이메일').fill(id)
  await page.getByLabel('비밀번호').fill(pw)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await page.waitForURL(u => !u.pathname.startsWith('/login'))
}

/** 콘솔 error, 페이지 예외, 5xx 응답을 모은다. 반환된 배열에 계속 쌓인다 */
export function collectProblems(page) {
  const problems = []
  page.on('pageerror', e => problems.push(`pageerror: ${e.message}`))
  page.on('console', m => { if (m.type() === 'error') problems.push(`console.error: ${m.text()}`) })
  page.on('response', r => { if (r.status() >= 500) problems.push(`HTTP ${r.status()}: ${r.url()}`) })
  return problems
}

export async function settle(page) {
  await page.waitForLoadState('networkidle').catch(() => {})
  await page.evaluate(() => document.fonts?.ready).catch(() => {})
  await page.waitForTimeout(250)
}
