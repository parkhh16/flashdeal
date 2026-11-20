// node shot.mjs <path> <width> <out.png> [admin]
// 헤드리스 Chrome을 띄워 지정한 창 너비로 페이지를 캡처하고, 가로 넘침을 잰다 (CDP 직접 사용, 설치할 라이브러리 없음)
// 환경변수: MAXH(캡처 높이 상한, 기본 4000), EVAL(페이지에서 실행해 결과를 출력할 JS), DEBUG(이동 과정 출력),
//          WEB(프론트 주소, 기본 http://127.0.0.1:5173), API(백엔드 주소, 기본 http://127.0.0.1:8080), CHROME(chrome.exe 경로)
// 사용법은 같은 폴더의 README.md
import { spawn } from 'node:child_process'
import { writeFileSync, mkdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const [path, widthArg, out, asAdmin] = process.argv.slice(2)
const width = Number(widthArg)
const PORT = 9333
const WEB = process.env.WEB || 'http://127.0.0.1:5173'
const API = process.env.API || 'http://127.0.0.1:8080'
const PROFILE = join(tmpdir(), 'flashdeal-shot-chrome') // 캡처용 Chrome 프로필 (평소 쓰는 Chrome과 분리)
mkdirSync(PROFILE, { recursive: true })
const chrome = spawn(process.env.CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe', [
  '--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
  '--no-first-run', '--disable-gpu', `--window-size=${width},900`, 'about:blank'], { stdio: 'ignore' })
const sleep = ms => new Promise(r => setTimeout(r, ms))

try {
  let targets
  for (let i = 0; i < 50; i++) {
    try { targets = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json(); if (targets.some(t => t.type === 'page')) break } catch { }
    await sleep(200)
  }
  const ws = new WebSocket(targets.find(t => t.type === 'page').webSocketDebuggerUrl)
  await new Promise(r => ws.addEventListener('open', r))
  let id = 0
  const pending = new Map()
  ws.addEventListener('message', e => { const m = JSON.parse(e.data); if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id) } })
  const send = (method, params = {}) => new Promise(r => { const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params })) })
  const evaluate = async expr => (await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })).result?.result?.value

  await send('Page.enable')
  await send('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: false })
  await send('Page.navigate', { url: `${WEB}/` })
  await sleep(1500)
  if (asAdmin) {
    const session = await (await fetch(`${API}/api/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ loginId: 'admin', password: 'admin' }) })).json()
    await evaluate(`localStorage.setItem('flashdeal.session', ${JSON.stringify(JSON.stringify(session))})`)
  }
  const nav = await send('Page.navigate', { url: `${WEB}${path}` })
  if (process.env.DEBUG) console.log('nav', JSON.stringify(nav))
  for (const t of [300, 1000, 2000]) { await sleep(t === 300 ? 300 : 700); if (process.env.DEBUG) console.log(t, await evaluate('location.pathname + " " + (localStorage.getItem("flashdeal.session")||"").slice(0,80)')) }
  await sleep(1000)
  const info = await evaluate(`(() => {
    const doc = document.documentElement
    const overflow = []
    document.querySelectorAll('body *').forEach(el => {
      const r = el.getBoundingClientRect()
      if (r.width > 0 && r.right > window.innerWidth + 1 && !el.closest('.table-wrap')) overflow.push(el.tagName.toLowerCase() + '.' + [...el.classList].join('.') + ' right=' + Math.round(r.right))
    })
    const wraps = [...document.querySelectorAll('.table-wrap')].map(w => 'table-wrap scroll ' + w.scrollWidth + '/' + w.clientWidth)
    return { at: location.pathname, innerWidth: window.innerWidth, pageScrollWidth: doc.scrollWidth, height: doc.scrollHeight, overflow: overflow.slice(0, 8), wraps }
  })()`)
  await send('Emulation.setDeviceMetricsOverride', { width, height: Math.min(info.height, Number(process.env.MAXH || 4000)), deviceScaleFactor: 1, mobile: false })
  await sleep(500)
  const shot = await send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(out, Buffer.from(shot.result.data, 'base64'))
  console.log(JSON.stringify(info))
  if (process.env.EVAL) console.log(JSON.stringify(await evaluate(process.env.EVAL)))
  ws.close()
} finally {
  chrome.kill()
}
