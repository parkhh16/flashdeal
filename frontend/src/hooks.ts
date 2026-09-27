import { useEffect, useState } from 'react'

/** 카운트다운용 현재 시각. 화면에 여러 개가 있어도 컴포넌트마다 1초 타이머 하나 */
export function useNow(intervalMs = 1000) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), intervalMs)
    return () => clearInterval(t)
  }, [intervalMs])
  return now
}
