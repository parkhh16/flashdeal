import { useEffect, useMemo, useRef, useState, type TouchEvent } from 'react'
import { Link } from 'react-router-dom'
import { AUTO_MS, type Slide } from '../bannerSlides'
import { LOW_STOCK_THRESHOLD, won } from '../format'
import { ChevronLeftIcon, ChevronRightIcon, PauseIcon, PlayIcon } from './icons'
import { ProductImage } from './ProductImage'

function useReducedMotion() {
  const [reduced, setReduced] = useState(() => window.matchMedia('(prefers-reduced-motion: reduce)').matches)
  useEffect(() => {
    const m = window.matchMedia('(prefers-reduced-motion: reduce)')
    const on = () => setReduced(m.matches)
    m.addEventListener('change', on)
    return () => m.removeEventListener('change', on)
  }, [])
  return reduced
}

function useTabHidden() {
  const [hidden, setHidden] = useState(() => document.hidden)
  useEffect(() => {
    const on = () => setHidden(document.hidden)
    document.addEventListener('visibilitychange', on)
    return () => document.removeEventListener('visibilitychange', on)
  }, [])
  return hidden
}

/** 상품 목록이 오기 전. 같은 높이를 잡아 두어 화면이 밀리지 않게 한다 */
export function BannerSkeleton() {
  return (
    <section className="banner banner-skeleton" aria-busy="true" aria-label="추천 상품을 불러오는 중">
      <div className="banner-inner" />
    </section>
  )
}

/**
 * 홈 맨 위 배너: 실제 상품이 5초마다 넘어간다.
 * - 멈출 수 있어야 한다(WCAG 2.2.2): 멈춤 버튼, 마우스를 올리거나 키보드로 들어오면 멈춤, 탭이 가려지면 멈춤
 * - 시스템에서 "동작 줄이기"를 켰으면 자동으로 넘기지 않는다 (버튼으로만)
 * - 화면 밖 슬라이드는 inert라 키보드와 화면 낭독기가 가지 않는다
 * - 상품이 없거나 목록이 실패하면 호출하는 쪽이 정적 히어로로 바꾼다 (홈은 배너 없이도 정상이어야 한다)
 */
export function HomeBanner({ slides }: { slides: Slide[] }) {
  const n = slides.length
  const [index, setIndex] = useState(0)
  const [userPaused, setUserPaused] = useState(false)
  const [engaged, setEngaged] = useState(false) // 마우스를 올렸거나 키보드 포커스가 안에 있음
  const reduced = useReducedMotion()
  const tabHidden = useTabHidden()
  const touchX = useRef<number | null>(null)

  const current = n === 0 ? 0 : index % n
  const playing = n > 1 && !userPaused && !engaged && !reduced && !tabHidden

  // 슬라이드를 직접 넘기면 시간이 다시 시작한다 (current가 바뀌면 이 효과가 다시 걸린다)
  useEffect(() => {
    if (!playing) return
    const t = setTimeout(() => setIndex(i => (i + 1) % n), AUTO_MS)
    return () => clearTimeout(t)
  }, [playing, current, n])

  const go = useMemo(() => (to: number) => setIndex(((to % n) + n) % n), [n])

  const onTouchStart = (e: TouchEvent) => { touchX.current = e.touches[0].clientX }
  const onTouchEnd = (e: TouchEvent) => {
    if (touchX.current === null) return
    const dx = e.changedTouches[0].clientX - touchX.current
    touchX.current = null
    if (Math.abs(dx) > 40) go(current + (dx < 0 ? 1 : -1))
  }

  return (
    <section className="banner" aria-roledescription="carousel" aria-label="추천 상품"
             onMouseEnter={() => setEngaged(true)} onMouseLeave={() => setEngaged(false)}
             onFocus={() => setEngaged(true)}
             onBlur={e => { if (!e.currentTarget.contains(e.relatedTarget)) setEngaged(false) }}>
      <h1 className="sr-only">니즈샵 추천 상품</h1>
      <div className="banner-inner">
        <div className="banner-viewport" onTouchStart={onTouchStart} onTouchEnd={onTouchEnd}
             aria-live={playing ? 'off' : 'polite'}>
          <div className="banner-track" style={{ transform: `translateX(-${current * 100}%)` }}>
            {slides.map((s, i) => {
              const p = s.product
              const active = i === current
              return (
                <div key={s.key} className="banner-slide" role="group" aria-roledescription="slide"
                     aria-label={`${n}장 중 ${i + 1}번째`} aria-hidden={!active} inert={!active}>
                  <div className="banner-text">
                    <span className="hero-eyebrow">{s.label}</span>
                    <h2><Link to={`/products/${p.id}`}>{p.name}</Link></h2>
                    <p className="banner-meta">{p.categoryLabel} · {p.subCategoryLabel}</p>
                    <div className="banner-price">
                      {p.discountRate > 0 && <span className="banner-discount">{p.discountRate}%</span>}
                      <strong>{won(p.price)}</strong>
                      {p.originalPrice != null && p.discountRate > 0 && <s>{won(p.originalPrice)}</s>}
                    </div>
                    {p.stock <= LOW_STOCK_THRESHOLD && <span className="banner-stock">{p.stock}개 남음</span>}
                    <Link to={`/products/${p.id}`} className="btn-primary banner-cta">바로 보기</Link>
                  </div>
                  <div className="banner-media"><ProductImage src={p.thumbnail} alt="" category={p.category} /></div>
                </div>
              )
            })}
          </div>
        </div>
        {n > 1 && (
          <div className="banner-controls">
            <button type="button" className="banner-btn" onClick={() => go(current - 1)} aria-label="이전 슬라이드"><ChevronLeftIcon /></button>
            <button type="button" className="banner-btn" onClick={() => setUserPaused(p => !p)}
                    aria-label={userPaused ? '자동 넘김 재생' : '자동 넘김 멈춤'} aria-pressed={userPaused}>
              {userPaused || reduced ? <PlayIcon /> : <PauseIcon />}
            </button>
            <button type="button" className="banner-btn" onClick={() => go(current + 1)} aria-label="다음 슬라이드"><ChevronRightIcon /></button>
            <div className="banner-dots">
              {slides.map((s, i) => (
                <button key={s.key} type="button" className="banner-dot" onClick={() => go(i)}
                        aria-label={`${i + 1}번째 슬라이드`} aria-current={i === current ? 'true' : undefined} />
              ))}
            </div>
          </div>
        )}
      </div>
    </section>
  )
}
