import { Link } from 'react-router-dom'
import type { Product } from '../api'
import { won } from '../format'
import { ProductImage } from './ProductImage'

/** 품절이면 배지를 보여준다. 남은 시간이나 판매율처럼 구매를 재촉하는 표시는 두지 않는다 */
export function StatusBadge({ product }: { product: Product }) {
  return product.soldOut ? <span className="badge soldout">품절</span> : null
}

export function ProductCard({ product }: { product: Product }) {
  return (
    <Link to={`/products/${product.id}`} className={`card ${product.soldOut ? 'dim' : ''}`}>
      <div className="card-media">
        <ProductImage src={product.thumbnail} alt={product.name} category={product.category} />
        <div className="card-badges"><StatusBadge product={product} /></div>
      </div>
      <div className="card-body">
        <div className="cat">{product.categoryLabel} · {product.subCategoryLabel}</div>
        <h4>{product.name}</h4>
        <div className="price-row">
          {product.discountRate > 0 && <span className="discount">{product.discountRate}%</span>}
          <span className="price">{won(product.price)}</span>
        </div>
        {product.originalPrice && product.discountRate > 0 && <div className="original">{won(product.originalPrice)}</div>}
        {product.perUserLimit && <div className="limit-note">1인 {product.perUserLimit}개 구매 가능</div>}
      </div>
    </Link>
  )
}
