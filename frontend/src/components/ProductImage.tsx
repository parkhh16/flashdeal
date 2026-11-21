import { useState } from 'react'
import { CategoryIcon } from './icons'

interface Props {
  src: string | null | undefined
  alt: string
  category: string
  /** 이미지가 없을 때 칸 안에 글자로 보여줄 카테고리 이름 */
  categoryLabel?: string
  className?: string
  eager?: boolean
}

/** 1:1 이미지. 이미지가 없거나 로딩에 실패하면 카테고리 플레이스홀더를 보여준다 */
export function ProductImage({ src, alt, category, categoryLabel, className = '', eager = false }: Props) {
  const [failed, setFailed] = useState(false)
  if (!src || failed) {
    return (
      <div className={`product-image placeholder ${className}`} role="img" aria-label={alt}>
        <CategoryIcon code={category} className="ph-icon" />
        {categoryLabel && <span className="ph-label" aria-hidden="true">{categoryLabel}</span>}
      </div>
    )
  }
  return (
    <div className={`product-image ${className}`}>
      <img src={src} alt={alt} loading={eager ? 'eager' : 'lazy'} decoding="async" onError={() => setFailed(true)} />
    </div>
  )
}
