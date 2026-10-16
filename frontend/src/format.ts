export const won = (n: number) => n.toLocaleString('ko-KR') + '원'

/** 남은 시간을 "3:12:05" / "12:34" 형태로 (주문의 결제 기한 표시용) */
export function remaining(ms: number) {
  if (ms <= 0) return '0:00'
  const s = Math.floor(ms / 1000)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`
  return `${m}:${String(sec).padStart(2, '0')}`
}

export const CATEGORY_ICON: Record<string, string> = {
  KEYBOARD: '⌨️', MOUSE: '🖱️', MONITOR: '🖥️', AUDIO: '🎧', ACCESSORY: '🧩',
}
