#!/usr/bin/env node
/**
 * 상품 이미지 수집 스크립트 (재현 가능)
 *
 *   node scripts/fetch-product-images.mjs
 *
 * - Wikimedia Commons에서 "자유 라이선스(CC0 / CC BY / CC BY-SA / Public domain)" 이미지만 사용한다.
 * - 후보는 사람이 콘택트 시트로 눈으로 확인하고 골랐고, 여기에는 고른 파일명만 고정(pin)해 둔다.
 * - 외부 URL을 핫링크하지 않고 frontend/public/images/products 에 저장한다. (ffmpeg가 있으면 800x800 정사각으로 크롭)
 * - 출처/작성자/라이선스는 IMAGE_CREDITS.md 에, 상품별 경로는 백엔드 시드용 manifest(JSON)에 기록한다.
 * - 알맞은 사진이 없던 상품은 직접 그린 SVG를 쓴다 (SVG_FALLBACK).
 */
import { execFileSync } from 'node:child_process'
import { mkdirSync, rmSync, writeFileSync, existsSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const IMG_DIR = join(ROOT, 'frontend/public/images/products')
const MANIFEST = join(ROOT, 'backend/src/main/resources/seed/product-images.json')
const CREDITS = join(ROOT, 'IMAGE_CREDITS.md')
const UA = { 'User-Agent': 'FlashDealPortfolio/1.0 (https://github.com; portfolio demo)' }
const ALLOWED = /^(CC0|CC BY(-SA)? \d\.\d|Public domain)/i

const PINNED = {
  'kb-limited': ['Beautiful Mechanical Keyboard.jpg', 'Mechanical Keyboard.jpg', 'MX brown key switches.jpg'],
  'kb-silent-wireless': ['Apple-wireless-keyboard.jpg', 'Apple-wireless-keyboard-aluminum-2007.jpg'],
  'kb-gaming-tkl': ['Backlit keyboard.jpg', 'Razer Arctosa keyboard-top-ar 16to9 PNr°0325.jpg'],
  'kb-aluminum': ['Key switches, (20260205-P1074852).jpg', 'Double-shot injection moulded keycaps.jpg'],
  'kb-slim-bt': ['Rii RT-MWK01 mini wireless keyboard HS1.jpg', 'Rii RT-MWK01 mini wireless keyboard HS3.jpg'],
  'ms-vertical': ['Hand on the computer mouse - 50202556601.jpg'],
  'ms-light-gaming': ['2023 Mysz komputerowa Logitech G903 Lightspeed.jpg'],
  'ms-silent': ['A wireless computer mouse.jpg', 'A black wireless computer mouse.jpg'],
  'ms-trackball': ['Kensington Expert Mouse Wired Trackball 64325.jpg', 'Logitech trackball mouse (7000976232).jpg',
    'Elecom M-XT1URBK (Wired M-XT1DRBK Variant) Trackball Mouse (22807080815).jpg'],
  'mn-27-qhd': ['EIZO Foris FG2421 VGA computer monitor displaying test pattern.png'],
  'mn-24-office': ['Brown and beige, Computer desk, Rostov-on-Don, Russia.jpg',
    'HP LCD monitor on adjustable desk mount inside TGF Pizza takeaway, Gillingham High Street, Kent.jpg'],
  'mn-24-240': ['Gaming PC-Setup - Astaroth- The Completed System.jpg'],
  'au-anc-headset': ['Bose QuietComfort 25 Acoustic Noise Cancelling Headphones with Carry Case.jpg', 'Headphones 1.jpg'],
  'au-earbuds': ['ActiveSound wireless earbuds by Hykker (POJM200483).jpg'],
  'au-desk-speaker': ['Bose Computer MusicMonitor.jpg', 'PC speakers adapter plug and 3.5mm audio plug.jpg', 'Logitech-usb-speakers.jpg'],
  'au-gaming-headset': ['Headphones on desk.jpg', 'Headphones with glasses.JPG'],
  'au-anc-earbuds': ['Yamaha TW-E3A Earbuds Customize, Japan; April 2021 (01).jpg'],
  'au-portable-speaker': ['JBL Flip 3 bluetooth speaker (DSCF2653).jpg',
    'JBL CHARGE3 という Portable Bluetooth Speaker 、疲れない音ですね。車載するよ！ (28494681635).jpg', 'UE Boom 2.jpg'],
  'ac-deskmat': ['HP mouse and mousepad 20060803.jpg', 'Logitech Red mouse on a mouse pad.jpg'],
  'ac-monitor-arm': ['Monitor arm stand (1).jpg', 'Multi-monitor workstations in the Wendelstein 7-X control room.jpg'],
  'ac-laptop-stand': ['Laptop stand.jpg'],
  'ac-keycaps': ['Orange Topre keycaps (6709763077).jpg', 'Keycaps.jpg', 'FILCO Calendar Keycap Set 2018 (38747213722).jpg'],
  'ac-usb-hub': ['USB HUB 2.0.jpg', 'USB hub Gembird.jpg'],
}

/** 적합한 무료 사진을 찾지 못해 직접 그린 SVG를 쓰는 상품 (frontend/public/images/products/*.svg, 저장소에 포함) */
const SVG_FALLBACK = {
  'ms-8k': ['ms-8k-1.svg'],
  'mn-32-4k': ['mn-32-4k-1.svg'],
}

const hasFfmpeg = (() => { try { execFileSync('ffmpeg', ['-version'], { stdio: 'ignore' }); return true } catch { return false } })()
const strip = (html = '') => html.replace(/<[^>]*>/g, '').replace(/\s+/g, ' ').trim()

async function imageInfo(titles) {
  const url = 'https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=imageinfo' +
    '&iiprop=url|extmetadata|mime&iiurlwidth=1000&titles=' + encodeURIComponent(titles.map(t => 'File:' + t).join('|'))
  const res = await (await fetch(url, { headers: UA })).json()
  const normalized = Object.fromEntries((res.query.normalized ?? []).map(n => [n.to, n.from]))
  return Object.values(res.query.pages).map(p => ({ requested: (normalized[p.title] ?? p.title).replace(/^File:/, ''), page: p }))
}

mkdirSync(IMG_DIR, { recursive: true })
mkdirSync(dirname(MANIFEST), { recursive: true })

const manifest = {}
const credits = []
for (const [slug, titles] of Object.entries(PINNED)) {
  const infos = await imageInfo(titles)
  manifest[slug] = []
  for (const [n, title] of titles.entries()) {
    const info = infos.find(i => i.requested === title || i.page.title === 'File:' + title)?.page.imageinfo?.[0]
    if (!info) { console.warn(`! ${slug}: '${title}' 를 찾지 못함`); continue }
    const meta = info.extmetadata
    const license = meta.LicenseShortName?.value ?? ''
    if (!ALLOWED.test(license)) { console.warn(`! ${slug}: 허용되지 않은 라이선스 '${license}' → 건너뜀`); continue }

    const file = `${slug}-${n + 1}.jpg`
    const target = join(IMG_DIR, file)
    const tmp = target + '.download'
    writeFileSync(tmp, Buffer.from(await (await fetch(info.thumburl, { headers: UA })).arrayBuffer()))
    if (hasFfmpeg) {
      execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-i', tmp, '-vf',
        "crop='min(iw,ih)':'min(iw,ih)',scale=800:800", '-frames:v', '1', '-q:v', '4', target])
      rmSync(tmp)
    } else {
      rmSync(target, { force: true })
      execFileSync(process.platform === 'win32' ? 'cmd' : 'mv', process.platform === 'win32' ? ['/c', 'move', '/y', tmp, target] : [tmp, target])
    }
    manifest[slug].push(`/images/products/${file}`)
    credits.push({
      file, slug, title, license,
      author: strip(meta.Artist?.value) || '알 수 없음',
      licenseUrl: meta.LicenseUrl?.value ?? '',
      source: info.descriptionurl,
    })
    console.log(`✓ ${file}  (${license})`)
  }
}
for (const [slug, files] of Object.entries(SVG_FALLBACK)) {
  manifest[slug] = files.filter(f => existsSync(join(IMG_DIR, f))).map(f => `/images/products/${f}`)
}

writeFileSync(MANIFEST, JSON.stringify(manifest, null, 2) + '\n')
writeFileSync(CREDITS, [
  '# IMAGE CREDITS',
  '',
  '상품 이미지는 모두 [Wikimedia Commons](https://commons.wikimedia.org)의 자유 라이선스 이미지입니다.',
  '`scripts/fetch-product-images.mjs`로 다운로드했으며, 정사각형(800x800)으로 가운데를 잘라낸 것 외에는 수정하지 않았습니다.',
  '데모 상품은 가상의 상품이고, 사진 속 실제 제조사/브랜드와는 관계가 없습니다 (분위기 연출용).',
  '',
  `직접 그린 SVG 일러스트: ${Object.values(SVG_FALLBACK).flat().join(', ')} (적합한 무료 사진을 찾지 못한 상품, 저작권 본 프로젝트)`,
  '',
  '| 파일 | 원본 | 작성자 | 라이선스 |',
  '|---|---|---|---|',
  ...credits.map(c => `| ${c.file} | [${c.title.replace(/\|/g, '\\|')}](${c.source}) | ${c.author.replace(/\|/g, '\\|')} | ${c.licenseUrl ? `[${c.license}](${c.licenseUrl})` : c.license} |`),
  '',
].join('\n'))
console.log(`\n이미지 ${credits.length}장, manifest → ${MANIFEST}`)
