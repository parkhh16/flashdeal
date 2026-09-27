import { useState } from 'react'
import { CATEGORY_ICON } from '../format'

interface Props {
  src: string | null | undefined
  alt: string
  category: string
  className?: string
  eager?: boolean
}

/** 1:1 이미지. 이미지가 없거나 로딩에 실패하면 카테고리 플레이스홀더를 보여준다 */
export function ProductImage({ src, alt, category, className = '', eager = false }: Props) {
  const [failed, setFailed] = useState(false)
  if (!src || failed) {
    return (
      <div className={`product-image placeholder ${className}`} role="img" aria-label={alt}>
        <span aria-hidden="true">{CATEGORY_ICON[category] ?? '📦'}</span>
      </div>
    )
  }
  return (
    <div className={`product-image ${className}`}>
      <img src={src} alt={alt} loading={eager ? 'eager' : 'lazy'} decoding="async" onError={() => setFailed(true)} />
    </div>
  )
}
