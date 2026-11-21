import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, type AiSearchResult, type CategoryTree, type Product, type SortKey } from '../api'
import { CategoryRail } from '../components/CategoryRail'
import { Sidebar } from '../components/Sidebar'
import { EmptySearchIcon } from '../components/icons'
import { ProductCard } from '../components/ProductCard'
import { useApp } from '../context/AppContext'
import { LOW_STOCK_THRESHOLD, won } from '../format'

const SORTS: { key: SortKey; label: string }[] = [
  { key: 'POPULAR', label: '인기순' },
  { key: 'PRICE_ASC', label: '낮은가격순' },
  { key: 'PRICE_DESC', label: '높은가격순' },
]

const PRICE_PRESETS = [
  { label: '전체 가격', min: undefined, max: undefined },
  { label: '3만원 이하', min: undefined, max: 30000 },
  { label: '3~10만원', min: 30000, max: 100000 },
  { label: '10~30만원', min: 100000, max: 300000 },
  { label: '30만원 이상', min: 300000, max: undefined },
]

const SUGGESTIONS = ['5만원 이하 무선 마우스', '10~30만원 모니터', '게이밍 헤드셋', '3만원 이하 키보드']

const num = (v: string | null) => (v && !Number.isNaN(Number(v)) ? Number(v) : undefined)

/** AI 검색 결과는 서버가 가격순으로 주므로, 화면의 정렬/품절 필터를 클라이언트에서 한 번 더 적용한다 */
function applyLocal(products: Product[], sort: SortKey, excludeSoldOut: boolean) {
  const list = excludeSoldOut ? products.filter(p => !p.soldOut) : [...products]
  const cmp: Record<SortKey, (a: Product, b: Product) => number> = {
    POPULAR: (a, b) => b.soldCount - a.soldCount,
    PRICE_ASC: (a, b) => a.price - b.price,
    PRICE_DESC: (a, b) => b.price - a.price,
  }
  return list.sort(cmp[sort])
}

function ExampleChips({ className, label }: { className: string; label?: string }) {
  return (
    <div className={className} role="group" aria-label={label ?? '예시 검색어'}>
      {SUGGESTIONS.map(s => <Link key={s} to={`/?q=${encodeURIComponent(s)}`}>{s}</Link>)}
    </div>
  )
}

/** 홈 히어로. 정적이고 상품 데이터에 의존하지 않는다 (목록이 로딩 중이거나 실패해도 그대로 보인다) */
function Hero() {
  return (
    <section className="hero" aria-labelledby="hero-title">
      <span className="hero-eyebrow">수량 한정 · 선착순 판매</span>
      <h1 id="hero-title">정해진 수량만큼만,<br />좋은 물건을 골랐어요</h1>
      <p>재고가 모두 팔리면 판매가 끝나요. 남은 수량은 상품마다 그대로 보여드려요.</p>
      <ExampleChips className="hero-examples" />
    </section>
  )
}

export function ShopPage() {
  const { fail } = useApp()
  const [params, setParams] = useSearchParams()
  const [products, setProducts] = useState<Product[] | null>(null)
  const [ai, setAi] = useState<AiSearchResult | null>(null)
  const [tree, setTree] = useState<CategoryTree | null>(null)
  const [failedQ, setFailedQ] = useState<string | null>(null)
  const [treeFailed, setTreeFailed] = useState(false)
  const [listFailed, setListFailed] = useState(false)

  const q = params.get('q')
  const searchFailed = !!q && failedQ === q
  const category = params.get('category') ?? undefined
  const sub = params.get('sub') ?? undefined
  const sort = (params.get('sort') as SortKey) || 'POPULAR'
  const minPrice = num(params.get('min'))
  const maxPrice = num(params.get('max'))
  const excludeSoldOut = params.get('soldout') === '0'
  const keyword = params.get('keyword') ?? undefined
  const exact = params.get('exact') === '1'

  useEffect(() => { api.categories().then(r => setTree(r.data)).catch(() => setTreeFailed(true)) }, [])

  useEffect(() => {
    let cancelled = false
    setProducts(null)
    setListFailed(false)
    if (q) {
      api.aiSearch(q, !exact).then(({ data }) => {
        if (cancelled) return
        setAi(data)
        setFailedQ(null)
        setProducts(data.products)
      }).catch(e => { if (!cancelled) { fail(e); setFailedQ(q); setProducts([]) } })
    } else {
      setAi(null)
      api.products({ category, sub, minPrice, maxPrice, excludeSoldOut, sort, keyword })
        .then(({ data }) => { if (!cancelled) setProducts(data) })
        .catch(e => { if (!cancelled) { fail(e); setListFailed(true); setProducts([]) } })
    }
    return () => { cancelled = true }
  }, [q, exact, category, sub, sort, minPrice, maxPrice, excludeSoldOut, keyword])

  const update = (patch: Record<string, string | undefined>) => {
    const next = new URLSearchParams(params)
    Object.entries(patch).forEach(([k, v]) => (v === undefined ? next.delete(k) : next.set(k, v)))
    setParams(next)
  }

  const visible = useMemo(
    () => (products && q ? applyLocal(products, sort, excludeSoldOut) : products),
    [products, q, sort, excludeSoldOut])

  // 홈 무필터: 히어로, "남은 수량이 적은 상품" 레일, 대표 카드는 이때만
  const isHome = !q && !category && !sub && !keyword && minPrice === undefined && maxPrice === undefined && !excludeSoldOut
  const lowStock = useMemo(
    () => (isHome && products
      ? products.filter(p => !p.soldOut && p.stock > 0 && p.stock <= LOW_STOCK_THRESHOLD).sort((a, b) => a.stock - b.stock).slice(0, 8)
      : []),
    [isHome, products])

  const catNode = tree?.categories.find(c => c.code === category)
  const subNode = catNode?.children.find(s => s.code === sub)
  const title = q ? `"${ai?.originalQuery ? ai.query : q}" 검색 결과` : subNode ? subNode.label : catNode ? catNode.label : '전체 상품'
  const activePreset = PRICE_PRESETS.findIndex(p => p.min === minPrice && p.max === maxPrice)
  const categoryLabel = (code: string | null | undefined) => tree?.categories.find(c => c.code === code)?.label ?? code ?? ''

  /**
   * 조건 칩. AI 검색 결과의 칩을 지우면, 해석된 나머지 조건을 URL 필터(category/min/max/keyword)로 옮겨
   * LLM을 다시 부르지 않고 일반 목록 API로 재검색한다.
   */
  const conditions: { key: string; label: string; remove: () => void }[] = []
  if (ai) {
    const base: Record<string, string | undefined> = {
      category: ai.filter.category ?? undefined,
      min: ai.filter.minPrice?.toString(),
      max: ai.filter.maxPrice?.toString(),
      keyword: ai.filter.keyword ?? undefined,
    }
    const without = (key: string) => () => {
      const next = new URLSearchParams()
      Object.entries(base).forEach(([k, v]) => { if (k !== key && v) next.set(k, v) })
      setParams(next)
    }
    if (base.category) conditions.push({ key: 'category', label: categoryLabel(base.category), remove: without('category') })
    if (base.min) conditions.push({ key: 'min', label: `${won(Number(base.min))} 이상`, remove: without('min') })
    if (base.max) conditions.push({ key: 'max', label: `${won(Number(base.max))} 이하`, remove: without('max') })
    if (base.keyword) conditions.push({ key: 'keyword', label: `"${base.keyword}"`, remove: without('keyword') })
  } else {
    // AI 칩에서 넘어온 필터라면 카테고리도 칩으로 보여줘서 끝까지 조건을 뺄 수 있게 한다
    if (category && (keyword || minPrice !== undefined || maxPrice !== undefined)) {
      conditions.push({ key: 'category', label: categoryLabel(category), remove: () => update({ category: undefined, sub: undefined }) })
    }
    if (keyword) conditions.push({ key: 'keyword', label: `"${keyword}"`, remove: () => update({ keyword: undefined }) })
    if (minPrice !== undefined) conditions.push({ key: 'min', label: `${won(minPrice)} 이상`, remove: () => update({ min: undefined }) })
    if (maxPrice !== undefined) conditions.push({ key: 'max', label: `${won(maxPrice)} 이하`, remove: () => update({ max: undefined }) })
  }

  const head = (
    <div className={`shop-head${isHome ? ' compact' : ''}`}>
      {!isHome && (
        <nav className="crumbs" aria-label="현재 위치">
          <Link to="/">홈</Link>
          {catNode && <> › <Link to={`/?category=${catNode.code}`}>{catNode.label}</Link></>}
          {subNode && <> › <span>{subNode.label}</span></>}
          {q && <> › <span>검색</span></>}
        </nav>
      )}
      <h2>{title} {visible && <span className="muted">{visible.length}개</span>}</h2>
      {ai?.originalQuery && (
        <p className="corrected">
          <strong>"{ai.originalQuery}"</strong> 검색 결과가 없어 <strong>"{ai.query}"</strong>(으)로 검색했어요.{' '}
          <Link to={`/?q=${encodeURIComponent(ai.originalQuery)}&exact=1`}>"{ai.originalQuery}"(으)로 검색하기</Link>
        </p>
      )}
      {ai?.match === 'SOME_WORDS' && (
        <p className="corrected">검색어의 단어가 모두 들어간 상품이 없어, 가장 많이 맞는 상품을 보여 드려요.</p>
      )}
      {ai?.match === 'RELAXED' && (
        <p className="corrected">
          <strong>"{ai.filter.keyword}"</strong>에 맞는 상품이 없어 {conditions.filter(c => c.key !== 'keyword').map(c => c.label).join(' · ')} 조건으로만 찾았어요.
        </p>
      )}
    </div>
  )

  return (
    <div className="shop">
      {isHome && <Hero />}
      {/* PC: 왼쪽 세로 카테고리 트리, 오른쪽 목록. 1023px 이하는 CSS가 트리를 숨기고 아래 가로 레일을 보여 준다 */}
      <div className="shop-layout">
        <Sidebar tree={tree} failed={treeFailed} category={category} sub={sub} searching={!!q} />
        <div className="shop-main">
          {!isHome && head}
          <CategoryRail tree={tree} failed={treeFailed} category={category} sub={sub} searching={!!q} />

          {lowStock.length > 0 && (
            <section className="low-stock" aria-labelledby="low-stock-title">
              <h2 id="low-stock-title">남은 수량이 적은 상품</h2>
              <p className="muted">수량이 소진되면 판매가 끝나요</p>
              <div className="rail-cards">{lowStock.map(p => <ProductCard key={p.id} product={p} />)}</div>
            </section>
          )}
          {isHome && head}

          {(ai || conditions.length > 0) && (
            <div className="parsed">
              <span className="muted">{ai ? '이렇게 이해했어요' : '적용된 조건'}</span>
              {conditions.map(c => (
                <span key={c.key} className="chip removable">
                  {c.label}
                  <button onClick={c.remove} aria-label={`${c.label} 조건 빼고 다시 검색`}>×</button>
                </span>
              ))}
              {ai?.filter.excludes?.map(e => <span key={`ex-${e}`} className="chip static">"{e}" 제외</span>)}
              {ai && conditions.length === 0 && <span className="chip static">가격·카테고리 조건 없음</span>}
              {conditions.length > 1 && <Link to="/" className="muted clear-link">모두 지우기</Link>}
            </div>
          )}

          <div className="toolbar">
            <div className="sort-tabs" role="tablist" aria-label="정렬">
              {SORTS.map(s => (
                <button key={s.key} role="tab" aria-selected={sort === s.key} className={sort === s.key ? 'active' : ''}
                        onClick={() => update({ sort: s.key === 'POPULAR' ? undefined : s.key })}>{s.label}</button>
              ))}
            </div>
            <div className="filters">
              {!q && (
                <select aria-label="가격대" value={activePreset < 0 ? 0 : activePreset}
                        onChange={e => {
                          const p = PRICE_PRESETS[Number(e.target.value)]
                          update({ min: p.min?.toString(), max: p.max?.toString() })
                        }}>
                  {PRICE_PRESETS.map((p, i) => <option key={p.label} value={i}>{p.label}</option>)}
                </select>
              )}
              <label className="check">
                <input type="checkbox" checked={excludeSoldOut} onChange={e => update({ soldout: e.target.checked ? '0' : undefined })} />
                품절 제외
              </label>
            </div>
          </div>

          {visible === null && (
            <div className="grid" aria-busy="true" aria-label="상품을 불러오는 중">
              {Array.from({ length: 8 }, (_, i) => (
                <div key={i} className="card skeleton" aria-hidden="true">
                  <div className="sk-img" />
                  <div className="card-body">
                    <div className="sk-line sk-cat" />
                    <div className="sk-name"><div className="sk-line" /><div className="sk-line short" /></div>
                    <div className="sk-line sk-price" />
                  </div>
                </div>
              ))}
            </div>
          )}
          {visible && visible.length === 0 && (
            <div className="empty-state">
              <EmptySearchIcon className="empty-icon" />
              <h3>{listFailed ? '상품을 불러오지 못했어요' : searchFailed ? '검색에 실패했어요' : '조건에 맞는 상품이 없어요'}</h3>
              {(listFailed || searchFailed)
                ? <p className="muted">잠시 뒤 다시 시도하거나 전체 상품을 둘러보세요</p>
                : <p className="muted">{conditions.length > 0 ? '위의 조건 칩을 하나씩 빼보거나, 이런 검색어는 어때요?' : '다른 검색어로 찾아보세요. 이런 검색어는 어때요?'}</p>}
              {!searchFailed && !listFailed && <ExampleChips className="empty-examples" />}
              <Link to="/" className="btn-primary">전체 상품 보기</Link>
            </div>
          )}
          {visible && visible.length > 0 && (
            <div className="grid">
              {visible.map((p, i) => <ProductCard key={p.id} product={p} featured={isHome && sort === 'POPULAR' && i === 0} />)}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
