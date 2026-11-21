// 흐름 E2E + 콘솔 에러 + 결제 중복 클릭 + 가로 스크롤 + axe. 사용: npm run e2e
import { chromium } from 'playwright'
import AxeBuilder from '@axe-core/playwright'
import { BASE, SCHEMES, SCREENS, VIEWPORTS, collectProblems, login, settle } from './lib.mjs'

const results = []
const check = (name, ok, detail = '') => { results.push({ name, ok, detail }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`) }

const browser = await chromium.launch()

// 주문 가능한 상품(재고 있음, 1인 제한 없음)을 API로 찾는다. 같은 DB로 여러 번 돌려도 제한에 안 걸리게
const list = await (await fetch(`${BASE}/api/products`)).json()
let target
for (const p of list) {
  if (p.soldOut) continue
  const d = await (await fetch(`${BASE}/api/products/${p.id}`)).json()
  if (!d.perUserLimit && d.stock > 5) { target = d; break }
}
if (!target) throw new Error('주문 가능한 상품이 없다')

// 1) 사용자 흐름: 로그인 -> 목록 -> 상세 -> 주문 -> 결제(더블클릭) -> 내 주문
{
  const ctx = await browser.newContext({ viewport: VIEWPORTS.pc })
  const page = await ctx.newPage()
  const problems = collectProblems(page)
  let payRequests = 0
  page.on('request', r => { if (r.method() === 'POST' && /\/api\/orders\/\d+\/payment/.test(r.url())) payRequests++ })

  await login(page, 'user'); check('로그인 후 이동', !page.url().includes('/login'))
  await page.goto(`${BASE}/`); await settle(page)
  check('상품 목록 표시', (await page.locator('a[href^="/products/"]').count()) > 0)
  await page.goto(`${BASE}/products/${target.id}`); await settle(page)
  const buy = page.getByRole('button', { name: '구매하기' })
  check('상세에 구매 버튼', await buy.isVisible())
  await buy.click()
  await page.waitForURL(/\/me/, { timeout: 5000 }).catch(() => {})
  await settle(page)
  const payBtn = page.getByRole('button', { name: '결제하기' }).first()
  check('주문 후 결제 버튼 노출', await payBtn.isVisible().catch(() => false))
  if (await payBtn.isVisible().catch(() => false)) {
    await payBtn.dblclick({ delay: 10 }) // 빠르게 두 번
    await page.waitForTimeout(1500)
    check('결제 버튼 더블클릭해도 결제 요청 1회', payRequests === 1, `요청 ${payRequests}회`)
  }
  check('사용자 흐름 중 콘솔 에러/5xx 0건', problems.length === 0, problems.join(' | '))
  await ctx.close()
}

// 2) 관리자 흐름
{
  const ctx = await browser.newContext({ viewport: VIEWPORTS.pc })
  const page = await ctx.newPage()
  const problems = collectProblems(page)
  await login(page, 'admin')
  for (const path of ['/admin', '/admin/products', '/admin/orders']) {
    await page.goto(`${BASE}${path}`); await settle(page)
    check(`관리자 ${path} 진입`, page.url().endsWith(path))
  }
  check('관리자 흐름 중 콘솔 에러/5xx 0건', problems.length === 0, problems.join(' | '))
  await ctx.close()
}

// 3) 일반 회원은 관리자 화면에 못 들어간다 (라우트 가드 회귀)
{
  const ctx = await browser.newContext({ viewport: VIEWPORTS.pc })
  const page = await ctx.newPage()
  await login(page, 'user')
  await page.goto(`${BASE}/admin`); await settle(page)
  check('일반 회원은 /admin 진입 불가', !page.url().endsWith('/admin') || (await page.getByText('대시보드').count()) === 0, page.url())
  await ctx.close()
}

// 3-a) 모바일 상세: 구매 버튼은 DOM에 1개, 빠르게 두 번 눌러도 POST /api/orders 1회
{
  const ctx = await browser.newContext({ viewport: VIEWPORTS.mobile })
  const page = await ctx.newPage()
  const problems = collectProblems(page)
  let orderRequests = 0
  page.on('request', r => { if (r.method() === 'POST' && /\/api\/orders\/?(\?|$)/.test(r.url())) orderRequests++ })
  await login(page, 'user')
  await page.goto(`${BASE}/products/${target.id}`); await settle(page)
  const buys = page.getByRole('button', { name: '구매하기' })
  check('모바일 상세: 구매하기 버튼 DOM에 정확히 1개', (await buys.count()) === 1, `${await buys.count()}개`)
  await buys.first().dblclick({ delay: 10 })
  await page.waitForTimeout(1500)
  check('모바일 상세: 구매 더블클릭해도 POST /api/orders 1회', orderRequests === 1, `요청 ${orderRequests}회`)
  check('모바일 상세 주문 중 콘솔 에러/5xx 0건', problems.length === 0, problems.join(' | '))
  await ctx.close()
}

// 3-b) 헤더 검색: /?q= 로 이동하고 목록 영역이 에러 화면이 아니다
{
  const ctx = await browser.newContext({ viewport: VIEWPORTS.pc })
  const page = await ctx.newPage()
  const problems = collectProblems(page)
  await page.goto(`${BASE}/`); await settle(page)
  await page.getByLabel('상품 검색').fill('키보드')
  await page.getByRole('button', { name: '검색', exact: true }).click()
  await page.waitForURL(/\/\?q=/, { timeout: 5000 }).catch(() => {})
  await settle(page)
  check('헤더 검색 제출 시 /?q= 로 이동', /\/\?q=/.test(page.url()), page.url())
  const errText = (await page.getByText(/불러오지 못했어요|검색에 실패했어요/).count())
  check('검색 결과 영역이 에러 화면이 아님', errText === 0)
  check('헤더 검색 중 콘솔 에러/5xx 0건', problems.length === 0, problems.join(' | '))
  await ctx.close()
}

// 3-c) 카테고리 API 500이어도 히어로, 헤더, 상품 그리드는 보인다 (부가 기능 실패 격리)
{
  const ctx = await browser.newContext({ viewport: VIEWPORTS.pc })
  const page = await ctx.newPage()
  await page.route('**/api/categories**', r => r.fulfill({ status: 500, contentType: 'application/json', body: '{}' }))
  await page.goto(`${BASE}/`); await settle(page)
  check('카테고리 500: 헤더 보임', await page.locator('header.header').isVisible())
  check('카테고리 500: 히어로 보임', await page.locator('section.hero').isVisible())
  check('카테고리 500: 상품 그리드 보임', (await page.locator('.grid a[href^="/products/"]').count()) > 0)
  await ctx.close()
}

// 3-d) 재고 부족/품절 표시
{
  const ctx = await browser.newContext({ viewport: VIEWPORTS.pc })
  const page = await ctx.newPage()
  await login(page, 'user')
  await page.goto(`${BASE}/products/2`); await settle(page)
  check('/products/2: "N개 남음" 문구 보임', await page.getByText(/\d+개 남음/).first().isVisible().catch(() => false))
  await page.goto(`${BASE}/products/4`); await settle(page)
  const soldBtn = page.locator('.buy-bar button.buy')
  check('/products/4 품절: 구매 버튼 비활성', (await soldBtn.count()) === 1 && await soldBtn.isDisabled())
  await ctx.close()
}

// 4) 화면별:콘솔 에러, 가로 스크롤, axe (모바일/PC x 라이트/다크)
for (const [vpName, viewport] of Object.entries(VIEWPORTS)) {
  for (const scheme of SCHEMES) {
    const ctx = await browser.newContext({ viewport, colorScheme: scheme })
    const page = await ctx.newPage()
    const problems = collectProblems(page)
    let current = null
    for (const s of SCREENS) {
      if (s.role !== current) {
        await page.evaluate(() => localStorage.clear()).catch(() => {})
        if (s.role) await login(page, s.role)
        current = s.role
      }
      problems.length = 0
      await page.goto(`${BASE}${s.path}`); await settle(page)
      const tag = `${s.name} ${vpName}/${scheme}`
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
      check(`${tag}: 가로 스크롤 없음`, overflow <= 0, `넘침 ${overflow}px`)
      const axe = await new AxeBuilder({ page }).analyze()
      const bad = axe.violations.filter(v => v.impact === 'serious' || v.impact === 'critical')
      check(`${tag}: axe serious/critical 0건`, bad.length === 0, bad.map(v => `${v.id}(${v.nodes.length})`).join(', '))
      check(`${tag}: 콘솔 에러/5xx 0건`, problems.length === 0, problems.join(' | '))
    }
    await ctx.close()
  }
}

await browser.close()
const failed = results.filter(r => !r.ok)
console.log(`\n통과 ${results.length - failed.length} / 실패 ${failed.length}`)
process.exit(failed.length ? 1 : 0)
