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

/** 이 수량 이하로 남았을 때만 "N개 남음"을 보여준다 (디자인 제안값. 3 / 5 / 10 중에서 바꿀 수 있게 한 곳에 둔다) */
export const LOW_STOCK_THRESHOLD = 10
