import { useEffect, useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { api, type CategoryTree } from '../api'
import { CATEGORY_ICON } from '../format'

/** 무신사식 카테고리 트리. 선택 상태는 URL 쿼리(category/sub/deal)가 기준이다 */
export function Sidebar({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [tree, setTree] = useState<CategoryTree | null>(null)
  const [params] = useSearchParams()
  const { pathname } = useLocation()
  const onShop = pathname === '/'
  const category = params.get('category')
  const sub = params.get('sub')
  const deal = params.get('deal') === '1'
  const searching = !!params.get('q')
  const [expanded, setExpanded] = useState<string | null>(category)

  useEffect(() => { api.categories().then(r => setTree(r.data)).catch(() => setTree(null)) }, [])
  useEffect(() => { if (category) setExpanded(category) }, [category])

  const isAll = onShop && !category && !sub && !deal && !searching

  return (
    <>
      <div className={`drawer-backdrop ${open ? 'show' : ''}`} onClick={onClose} aria-hidden="true" />
      <aside className={`sidebar ${open ? 'open' : ''}`} aria-label="카테고리">
        <div className="sidebar-head">
          <strong>카테고리</strong>
          <button className="icon-btn only-mobile" onClick={onClose} aria-label="닫기">✕</button>
        </div>
        <nav>
          <Link to="/" className={`side-item top ${isAll ? 'active' : ''}`}>
            <span>전체</span>{tree && <span className="count">{tree.total}</span>}
          </Link>
          <Link to="/?deal=1&sort=DEADLINE" className={`side-item top deal ${onShop && deal ? 'active' : ''}`}>
            <span>⚡ 오늘의 특가</span>{tree && <span className="count">{tree.deals}</span>}
          </Link>
          <div className="side-divider" />
          {tree?.categories.map(c => {
            const activeCat = onShop && category === c.code && !sub
            const isOpen = expanded === c.code
            return (
              <div key={c.code} className="side-group">
                <div className="side-row">
                  <Link to={`/?category=${c.code}`} className={`side-item ${activeCat ? 'active' : ''} ${category === c.code ? 'within' : ''}`}>
                    <span><span className="cat-icon" aria-hidden="true">{CATEGORY_ICON[c.code]}</span>{c.label}</span>
                    <span className="count">{c.count}</span>
                  </Link>
                  <button className="icon-btn" aria-expanded={isOpen} aria-label={`${c.label} 소분류 ${isOpen ? '접기' : '펼치기'}`}
                          onClick={() => setExpanded(isOpen ? null : c.code)}>{isOpen ? '−' : '+'}</button>
                </div>
                {isOpen && (
                  <div className="side-children">
                    {c.children.map(s => (
                      <Link key={s.code} to={`/?category=${c.code}&sub=${s.code}`}
                            className={`side-item child ${onShop && sub === s.code ? 'active' : ''} ${s.count === 0 ? 'empty' : ''}`}>
                        <span>{s.label}</span><span className="count">{s.count}</span>
                      </Link>
                    ))}
                  </div>
                )}
              </div>
            )
          })}
          {!tree && <p className="muted pad">카테고리를 불러오는 중…</p>}
        </nav>
      </aside>
    </>
  )
}
