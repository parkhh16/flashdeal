import { Link } from 'react-router-dom'
import type { Product } from '../api'
import { remaining, soldRatio, time, won } from '../format'
import { useNow } from '../hooks'
import { ProductImage } from './ProductImage'

const HOUR = 60 * 60 * 1000

export function DealBadge({ product, now }: { product: Product; now: number }) {
  switch (product.dealStatus) {
    case 'ONGOING': {
      const left = new Date(product.dealEndAt!).getTime() - now
      return left < HOUR
        ? <span className="badge hot">마감 임박 {remaining(left)}</span>
        : <span className="badge deal">특가 {remaining(left)} 남음</span>
    }
    case 'UPCOMING':
      return <span className="badge upcoming">{time(product.dealStartAt!)} 오픈</span>
    case 'SOLD_OUT':
      return <span className="badge soldout">특가 품절</span>
    case 'ENDED':
      return <span className="badge ended">특가 종료</span>
    default:
      return product.stock === 0 ? <span className="badge soldout">품절</span> : null
  }
}

export function StockBar({ product }: { product: Product }) {
  const ratio = soldRatio(product)
  if (ratio === null) return null
  return (
    <div className="stockbar" aria-label={`한정 ${product.dealQuantity}개 중 ${Math.round(ratio * 100)}% 판매`}>
      <div className="stockbar-track"><div className="stockbar-fill" style={{ width: `${ratio * 100}%` }} /></div>
      <span>{Math.round(ratio * 100)}% 판매 · 남은 {product.stock}개</span>
    </div>
  )
}

export function ProductCard({ product }: { product: Product }) {
  const now = useNow()
  const unavailable = product.stock === 0 || product.dealStatus === 'ENDED'
  return (
    <Link to={`/products/${product.id}`} className={`card ${unavailable ? 'dim' : ''}`}>
      <div className="card-media">
        <ProductImage src={product.thumbnail} alt={product.name} category={product.category} />
        <div className="card-badges"><DealBadge product={product} now={now} /></div>
      </div>
      <div className="card-body">
        <div className="cat">{product.categoryLabel} · {product.subCategoryLabel}</div>
        <h4>{product.name}</h4>
        <div className="price-row">
          {product.discountRate > 0 && <span className="discount">{product.discountRate}%</span>}
          <span className="price">{won(product.price)}</span>
        </div>
        {product.originalPrice && product.discountRate > 0 && <div className="original">{won(product.originalPrice)}</div>}
        {product.dealStatus === 'ONGOING' && <StockBar product={product} />}
        {product.dealStatus === 'NONE' && product.stock > 0 && product.stock < 30 && (
          <div className="low-stock">품절 임박 · {product.stock}개 남음</div>
        )}
      </div>
    </Link>
  )
}
