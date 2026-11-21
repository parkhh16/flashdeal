import { Link } from 'react-router-dom'
import type { CategoryTree } from '../api'
import { CategoryIcon, GridIcon } from './icons'

interface Props {
  tree: CategoryTree | null
  /** 트리 요청이 실패했는지. 실패해도 "전체" 칩 하나만 보이고 목록은 그대로다 */
  failed: boolean
  category?: string
  sub?: string
  /** 검색 중이면 어떤 칩도 선택 표시하지 않는다 */
  searching: boolean
}

/** 가로 카테고리 레일 + 소분류 줄. 선택 상태는 URL 쿼리(category/sub)가 기준이다 */
export function CategoryRail({ tree, failed, category, sub, searching }: Props) {
  const catNode = tree?.categories.find(c => c.code === category)
  const allActive = !category && !sub && !searching
  return (
    <div className="cat-nav">
      <nav className="cat-rail" aria-label="카테고리">
        <Link to="/" className="cat-chip" aria-current={allActive ? 'page' : undefined}>
          <GridIcon />
          <span>전체</span>
          {tree && <span className="count">{tree.total}</span>}
        </Link>
        {!tree && !failed && Array.from({ length: 5 }, (_, i) => <span key={i} className="cat-chip sk" aria-hidden="true" />)}
        {tree?.categories.map(c => (
          <Link key={c.code} to={`/?category=${c.code}`} className="cat-chip"
                aria-current={!searching && category === c.code && !sub ? 'page' : undefined}>
            <CategoryIcon code={c.code} />
            <span>{c.label}</span>
            <span className="count">{c.count}</span>
          </Link>
        ))}
      </nav>
      {catNode && catNode.children.length > 0 && !searching && (
        <nav className="sub-rail" aria-label={`${catNode.label} 소분류`}>
          {catNode.children.map(s => (
            <Link key={s.code} to={`/?category=${catNode.code}&sub=${s.code}`}
                  className={`sub-chip${s.count === 0 ? ' zero' : ''}`} aria-current={sub === s.code ? 'page' : undefined}>
              <span>{s.label}</span><span className="count">{s.count}</span>
            </Link>
          ))}
        </nav>
      )}
    </div>
  )
}
