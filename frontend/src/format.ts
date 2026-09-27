import type { Product } from './api'

export const won = (n: number) => n.toLocaleString('ko-KR') + '원'

export const time = (iso: string) =>
  new Date(iso).toLocaleString('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })

/** 남은 시간을 "3시간 12분" / "12:34" 형태로 */
export function remaining(ms: number) {
  if (ms <= 0) return '0:00'
  const s = Math.floor(ms / 1000)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`
  return `${m}:${String(sec).padStart(2, '0')}`
}

/** 특가 진행률(판매된 비율). 한정 수량 정보가 없으면 null */
export function soldRatio(p: Product) {
  if (!p.dealQuantity) return null
  return Math.min(1, Math.max(0, (p.dealQuantity - p.stock) / p.dealQuantity))
}

export const CATEGORY_ICON: Record<string, string> = {
  KEYBOARD: '⌨️', MOUSE: '🖱️', MONITOR: '🖥️', AUDIO: '🎧', ACCESSORY: '🧩',
}
