// npm run logo : src/logo.ts의 도형과 src/index.css의 강조색(--accent)으로 public/favicon.svg를 만든다.
// 로고를 바꾼 뒤 한 번 실행하면 브라우저 탭 아이콘이 헤더 로고와 같아진다.
import { readFileSync, writeFileSync } from 'node:fs'

const logo = readFileSync(new URL('../src/logo.ts', import.meta.url), 'utf8')
// 설명 주석에도 백틱이 있으므로, LOGO_INNER 선언 이후의 문자열만 읽는다
const decl = logo.slice(logo.indexOf('export const LOGO_INNER'))
const parts = [...decl.matchAll(/`([^`]*)`/g)].map(m => m[1])
if (parts.length === 0) throw new Error('src/logo.ts에서 도형(LOGO_INNER)을 찾지 못했다')
const inner = parts.join('')

const css = readFileSync(new URL('../src/index.css', import.meta.url), 'utf8')
const accent = css.match(/--accent:\s*(#[0-9a-fA-F]{6})/)?.[1]
if (!accent) throw new Error('src/index.css에서 --accent 색을 찾지 못했다')

// 16px 탭 아이콘에서도 읽히도록 헤더 로고보다 선을 굵게, 도형은 칸을 더 채우게 한다
const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
  <rect width="24" height="24" rx="6" fill="${accent}"/>
  <g transform="translate(1.7 1.5) scale(.86)" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">${inner}</g>
</svg>
`
writeFileSync(new URL('../public/favicon.svg', import.meta.url), svg)
console.log(`public/favicon.svg 생성 (강조색 ${accent})`)
