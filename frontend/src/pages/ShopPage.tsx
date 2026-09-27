import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { api, type AiSearchResult, type CategoryTree, type Product, type SortKey } from '../api'
import { ProductCard } from '../components/ProductCard'
import { useApp } from '../context/AppContext'
import { won } from '../format'

const SORTS: { key: SortKey; label: string }[] = [
  { key: 'POPULAR', label: '인기순' },
  { key: 'PRICE_ASC', label: '낮은가격순' },
  { key: 'PRICE_DESC', label: '높은가격순' },
  { key: 'DEADLINE', label: '마감임박순' },
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
  const list = excludeSoldOut ? products.filter(p => p.stock > 0) : [...products]
  const deadline = (p: Product) => (p.dealStatus === 'ONGOING' || p.dealStatus === 'UPCOMING') && p.dealEndAt
    ? new Date(p.dealEndAt).getTime() : Number.MAX_SAFE_INTEGER
  const cmp: Record<SortKey, (a: Product, b: Product) => number> = {
    POPULAR: (a, b) => b.soldCount - a.soldCount,
    PRICE_ASC: (a, b) => a.price - b.price,
    PRICE_DESC: (a, b) => b.price - a.price,
    DEADLINE: (a, b) => deadline(a) - deadline(b),
  }
  return list.sort(cmp[sort])
}

/** 상품 목록 위의 검색창. 검색어가 없을 때는 예시 검색어를 보여줘서 "자연어로 검색할 수 있다"는 걸 알려준다 */
function SearchBox({ current }: { current: string | null }) {
  const navigate = useNavigate()
  const [value, setValue] = useState(current ?? '')
  useEffect(() => setValue(current ?? ''), [current])

  const submit = (e: FormEvent) => {
    e.preventDefault()
    const q = value.trim()
    navigate(q ? `/?q=${encodeURIComponent(q)}` : '/')
  }

  return (
    <form className="search-box" onSubmit={submit} role="search">
      <div className="search-field">
        <span className="search-icon" aria-hidden="true">🔍</span>
        <input value={value} onChange={e => setValue(e.target.value)} aria-label="상품 검색"
               placeholder="찾는 상품을 말하듯이 입력해보세요 — 예: 5만원 이하 무선 마우스" />
        {value && <button type="button" className="clear" onClick={() => { setValue(''); navigate('/') }} aria-label="검색어 지우기">×</button>}
        <button type="submit">검색</button>
      </div>
      {!current && (
        <div className="search-examples">
          <span className="muted">이렇게 검색해보세요</span>
          {SUGGESTIONS.map(s => <Link key={s} to={`/?q=${encodeURIComponent(s)}`} className="chip">{s}</Link>)}
        </div>
      )}
    </form>
  )
}

export function ShopPage() {
  const { fail } = useApp()
  const [params, setParams] = useSearchParams()
  const [products, setProducts] = useState<Product[] | null>(null)
  const [ai, setAi] = useState<AiSearchResult | null>(null)
  const [tree, setTree] = useState<CategoryTree | null>(null)

  const q = params.get('q')
  const category = params.get('category') ?? undefined
  const sub = params.get('sub') ?? undefined
  const deal = params.get('deal') === '1'
  const sort = (params.get('sort') as SortKey) || 'POPULAR'
  const minPrice = num(params.get('min'))
  const maxPrice = num(params.get('max'))
  const excludeSoldOut = params.get('soldout') === '0'
  const keyword = params.get('keyword') ?? undefined
  const exact = params.get('exact') === '1'

  useEffect(() => { api.categories().then(r => setTree(r.data)).catch(() => undefined) }, [])

  useEffect(() => {
    let cancelled = false
    setProducts(null)
    if (q) {
      api.aiSearch(q, !exact).then(({ data }) => {
        if (cancelled) return
        setAi(data)
        setProducts(data.products)
      }).catch(e => { if (!cancelled) { fail(e); setProducts([]) } })
    } else {
      setAi(null)
      api.products({ category, sub, deal, minPrice, maxPrice, excludeSoldOut, sort, keyword })
        .then(({ data }) => { if (!cancelled) setProducts(data) })
        .catch(e => { if (!cancelled) { fail(e); setProducts([]) } })
    }
    return () => { cancelled = true }
  }, [q, exact, category, sub, deal, sort, minPrice, maxPrice, excludeSoldOut, keyword])

  const update = (patch: Record<string, string | undefined>) => {
    const next = new URLSearchParams(params)
    Object.entries(patch).forEach(([k, v]) => (v === undefined ? next.delete(k) : next.set(k, v)))
    setParams(next)
  }

  const visible = useMemo(
    () => (products && q ? applyLocal(products, sort, excludeSoldOut) : products),
    [products, q, sort, excludeSoldOut])

  const catNode = tree?.categories.find(c => c.code === category)
  const subNode = catNode?.children.find(s => s.code === sub)
  const title = q ? `"${ai?.originalQuery ? ai.query : q}" 검색 결과` : deal ? '⚡ 오늘의 특가' : subNode ? subNode.label : catNode ? catNode.label : '전체 상품'
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

  return (
    <div className="shop">
      <SearchBox current={q} />
      <div className="shop-head">
        <nav className="crumbs" aria-label="현재 위치">
          <Link to="/">홈</Link>
          {catNode && <> › <Link to={`/?category=${catNode.code}`}>{catNode.label}</Link></>}
          {subNode && <> › <span>{subNode.label}</span></>}
          {deal && <> › <span>오늘의 특가</span></>}
          {q && <> › <span>검색</span></>}
        </nav>
        <h2>{title} {visible && <span className="muted">{visible.length}개</span>}</h2>
        {ai?.originalQuery && (
          <p className="corrected">
            <strong>"{ai.originalQuery}"</strong> 검색 결과가 없어 <strong>"{ai.query}"</strong>(으)로 검색했어요.{' '}
            <Link to={`/?q=${encodeURIComponent(ai.originalQuery)}&exact=1`}>"{ai.originalQuery}"(으)로 검색하기</Link>
          </p>
        )}
      </div>

      {(ai || conditions.length > 0) && (
        <div className="parsed">
          <span className="muted">{ai ? '이렇게 이해했어요' : '적용된 조건'}</span>
          {conditions.map(c => (
            <span key={c.key} className="chip removable">
              {c.label}
              <button onClick={c.remove} aria-label={`${c.label} 조건 빼고 다시 검색`}>×</button>
            </span>
          ))}
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
        <div className="grid">
          {Array.from({ length: 8 }, (_, i) => <div key={i} className="card skeleton" aria-hidden="true"><div className="sk-img" /><div className="sk-line" /><div className="sk-line short" /></div>)}
        </div>
      )}
      {visible && visible.length === 0 && (
        <div className="empty">
          <p>조건에 맞는 상품이 없어요.</p>
          {conditions.length > 0 && <p className="muted">위의 조건 칩을 하나씩 빼보거나, 이런 검색어는 어때요?</p>}
          <div className="suggestions">
            {SUGGESTIONS.map(s => <Link key={s} to={`/?q=${encodeURIComponent(s)}`} className="chip">{s}</Link>)}
          </div>
          <Link to="/" className="ghost-link">전체 상품 보기</Link>
        </div>
      )}
      {visible && visible.length > 0 && (
        <div className="grid">{visible.map(p => <ProductCard key={p.id} product={p} />)}</div>
      )}
    </div>
  )
}
