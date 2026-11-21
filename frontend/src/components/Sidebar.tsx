import { Link } from 'react-router-dom'
import type { CategoryTree } from '../api'
import { CategoryIcon, GridIcon } from './icons'

interface Props {
  tree: CategoryTree | null
  /** 트리 요청이 실패했는지. 실패해도 "전체" 하나만 보이고 목록은 그대로다 */
  failed: boolean
  category?: string
  sub?: string
  /** 검색 중이면 어떤 항목도 선택 표시하지 않는다 */
  searching: boolean
}

/**
 * PC(1024px 이상)의 세로 카테고리 트리. 카테고리가 늘어도 세로로 쌓이므로 가로 레일처럼 넘치지 않는다.
 * 1023px 이하에서는 CSS가 숨기고 CategoryRail(가로 칩)을 보여 준다. 선택 상태는 URL 쿼리(category/sub)가 기준이고,
 * 소분류는 선택된 대분류 아래에만 펼쳐진다.
 */
export function Sidebar({ tree, failed, category, sub, searching }: Props) {
  const allActive = !category && !sub && !searching
  return (
    <aside className="sidebar" aria-label="카테고리">
      <h2 className="sidebar-title">카테고리</h2>
      <nav aria-label="카테고리 목록">
        <ul>
          <li>
            <Link to="/" className="side-link" aria-current={allActive ? 'page' : undefined}>
              <GridIcon />
              <span>전체</span>
              {tree && <span className="count">{tree.total}</span>}
            </Link>
          </li>
          {!tree && !failed && Array.from({ length: 5 }, (_, i) => <li key={i} className="side-sk" aria-hidden="true" />)}
          {tree?.categories.map(c => {
            const open = !searching && category === c.code && c.children.length > 0
            return (
              <li key={c.code}>
                <Link to={`/?category=${c.code}`} className="side-link"
                      aria-current={!searching && category === c.code && !sub ? 'page' : undefined}>
                  <CategoryIcon code={c.code} />
                  <span>{c.label}</span>
                  <span className="count">{c.count}</span>
                </Link>
                {open && (
                  <ul className="side-sub" aria-label={`${c.label} 소분류`}>
                    {c.children.map(s => (
                      <li key={s.code}>
                        <Link to={`/?category=${c.code}&sub=${s.code}`}
                              className={`side-sublink${s.count === 0 ? ' zero' : ''}`}
                              aria-current={sub === s.code ? 'page' : undefined}>
                          <span>{s.label}</span><span className="count">{s.count}</span>
                        </Link>
                      </li>
                    ))}
                  </ul>
                )}
              </li>
            )
          })}
        </ul>
      </nav>
    </aside>
  )
}
