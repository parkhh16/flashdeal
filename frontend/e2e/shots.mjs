// 고정 화면 x (모바일/PC) x (라이트/다크) 스크린샷. 사용: npm run shots -- <출력 폴더>
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
import { BASE, SCHEMES, SCREENS, VIEWPORTS, login, settle } from './lib.mjs'

const out = process.argv[2] ?? '../docs/design/shots/current'
mkdirSync(out, { recursive: true })
const browser = await chromium.launch()
let n = 0
for (const [vpName, viewport] of Object.entries(VIEWPORTS)) {
  for (const scheme of SCHEMES) {
    const ctx = await browser.newContext({ viewport, colorScheme: scheme, deviceScaleFactor: 1 })
    const page = await ctx.newPage()
    const loggedIn = new Set()
    for (const s of SCREENS) {
      if (s.role && !loggedIn.has(s.role)) {
        // 로그인 상태는 한 컨텍스트에 하나만 둔다: 역할이 바뀌면 로그아웃하고 다시 로그인
        await page.evaluate(() => localStorage.clear()).catch(() => {})
        await login(page, s.role)
        loggedIn.clear(); loggedIn.add(s.role)
      }
      if (!s.role && loggedIn.size) { await page.evaluate(() => localStorage.clear()); loggedIn.clear() }
      await page.goto(`${BASE}${s.path}`)
      await settle(page)
      await page.screenshot({ path: `${out}/${s.name}.${vpName}.${scheme}.png`, fullPage: true })
      n++
    }
    await ctx.close()
  }
}
await browser.close()
console.log(`스크린샷 ${n}장 -> ${out}`)
