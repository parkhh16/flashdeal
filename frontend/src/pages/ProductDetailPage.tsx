import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError, type ProductDetail } from '../api'
import { ProductCard } from '../components/ProductCard'
import { ProductImage } from '../components/ProductImage'
import { useApp } from '../context/AppContext'
import { BoxIcon, ChevronDownIcon } from '../components/icons'
import { LOW_STOCK_THRESHOLD, won } from '../format'

export function ProductDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { session, fail, toast, bumpOrders } = useApp()
  const [product, setProduct] = useState<ProductDetail | null>(null)
  const [notFound, setNotFound] = useState(false)
  const [imageIndex, setImageIndex] = useState(0)
  const [quantity, setQuantity] = useState(1)
  const [ordering, setOrdering] = useState(false)
  const inFlight = useRef(false)

  const load = () => api.product(Number(id))
    .then(r => { setProduct(r.data); setNotFound(false) })
    .catch(e => { if (e instanceof ApiError && e.status === 404) setNotFound(true); else fail(e) })

  useEffect(() => {
    setProduct(null)
    setImageIndex(0)
    setQuantity(1)
    load()
  }, [id])

  if (notFound) return (
    <div className="empty-state">
      <BoxIcon className="empty-icon" />
      <h3>상품을 찾을 수 없어요</h3>
      <p>주소가 바뀌었거나 판매가 끝난 상품일 수 있어요.</p>
      <Link to="/" className="btn-primary">상품 목록으로</Link>
    </div>
  )
  if (!product) {
    return (
      <div className="detail-page">
        <div className="detail skeleton" aria-busy="true" aria-label="상품 정보를 불러오는 중">
          <div className="sk-img" />
          <div className="sk-panel">
            <div className="sk-line short" /><div className="sk-line sk-title" /><div className="sk-line" /><div className="sk-line short" />
            <div className="sk-btn" />
          </div>
        </div>
      </div>
    )
  }

  const images = product.images.length > 0 ? product.images : [null]
  const maxQty = Math.max(1, Math.min(product.stock, product.perUserLimit ?? 10, 10))
  const soldOut = product.soldOut || product.stock <= 0
  const blocked = soldOut

  const buttonLabel = !session ? '로그인하고 구매하기'
    : soldOut ? '품절'
      : ordering ? '주문 중…' : '구매하기'

  /** 한 번의 클릭 = 멱등 키 하나. 요청 중에는 버튼과 ref로 이중 제출을 막는다 */
  const buy = async () => {
    if (inFlight.current || blocked || !session) return
    inFlight.current = true
    setOrdering(true)
    try {
      await api.createOrder(product.id, quantity, crypto.randomUUID())
      toast('주문이 접수되었어요. 10분 안에 결제를 완료해주세요.', 'success')
      bumpOrders()
      navigate('/me')
    } catch (e) {
      fail(e)
      load()
    } finally {
      inFlight.current = false
      setOrdering(false)
    }
  }

  const lowStock = !soldOut && product.stock <= LOW_STOCK_THRESHOLD

  return (
    <div className="detail-page">
      <div className="detail">
        <div className="gallery">
          <ProductImage src={images[imageIndex]} alt={`${product.name} 이미지 ${imageIndex + 1}`} category={product.category} categoryLabel={product.categoryLabel} eager />
          {images.length > 1 && (
            <div className="thumbs">
              {images.map((src, i) => (
                <button key={i} className={i === imageIndex ? 'active' : ''} onClick={() => setImageIndex(i)} aria-label={`이미지 ${i + 1} 보기`} aria-current={i === imageIndex}>
                  <ProductImage src={src} alt="" category={product.category} />
                </button>
              ))}
            </div>
          )}
        </div>

        <div className="buy-box">
          <nav className="crumbs" aria-label="카테고리">
            <Link to={`/?category=${product.category}`}>{product.categoryLabel}</Link> ›{' '}
            <Link to={`/?category=${product.category}&sub=${product.subCategory}`}>{product.subCategoryLabel}</Link>
          </nav>
          <h1>{product.name}</h1>
          <p className="buy-desc">{product.description}</p>

          <div className="price-block">
            {product.discountRate > 0 && <span className="discount big">{product.discountRate}%</span>}
            <span className="price big">{won(product.price)}</span>
            {product.discountRate > 0 && product.originalPrice && <span className="original">{won(product.originalPrice)}</span>}
          </div>

          <div className="status-line">
            {soldOut ? <span className="soldout-chip">품절</span>
              : lowStock ? <span className="left-chip">{product.stock}개 남음</span>
                : <span className="stock-text">재고 {product.stock}개</span>}
            {product.perUserLimit && <span className="limit-chip">1인 {product.perUserLimit}개까지</span>}
          </div>

          <div className="qty-row">
            <span>수량</span>
            <div className="qty-col">
              <div className="qty" role="group" aria-label="수량 선택">
                <button className="ghost" onClick={() => setQuantity(q => Math.max(1, q - 1))} disabled={quantity <= 1 || blocked || ordering} aria-label="수량 감소">−</button>
                <output aria-live="polite">{quantity}</output>
                <button className="ghost" onClick={() => setQuantity(q => Math.min(maxQty, q + 1))} disabled={quantity >= maxQty || blocked || ordering} aria-label="수량 증가">+</button>
              </div>
              <p className="qty-max" aria-live="polite">{!soldOut && quantity >= maxQty ? `최대 ${maxQty}개` : ''}</p>
            </div>
          </div>

          {/* 구매 바: DOM에 하나. 모바일에서는 CSS로 화면 아래에 고정한다 */}
          <div className="buy-bar">
            <div className="buy-total">
              <span>합계</span>
              <strong>{won(product.price * quantity)}</strong>
            </div>
            {session
              ? <button className="buy" onClick={buy} disabled={blocked || ordering} aria-busy={ordering}>{buttonLabel}</button>
              : <button className="buy" onClick={() => navigate(`/login?next=${encodeURIComponent(`/products/${product.id}`)}`)}>{buttonLabel}</button>}
          </div>

          <p className="buy-notes">무료배송 · 결제 후 1~2일 내 출고<br />주문 후 10분 안에 결제하지 않으면 자동 취소돼요.</p>
          {!session && <p className="buy-notes">로그인하면 이 페이지로 다시 돌아와요.</p>}
        </div>
      </div>

      <section className="detail-section">
        <h2>상품 설명</h2>
        <p>{product.detail ?? product.description}</p>
      </section>

      {Object.keys(product.specs).length > 0 && (
        <section className="detail-section">
          <h2>상품 정보</h2>
          <div className="spec-box">
            <table className="spec-table">
              <tbody>
                {Object.entries(product.specs).map(([k, v]) => <tr key={k}><th scope="row">{k}</th><td>{v}</td></tr>)}
              </tbody>
            </table>
          </div>
        </section>
      )}

      <details className="detail-section notice-box">
        <summary>배송 · 교환 · 반품 안내<ChevronDownIcon className="chev" /></summary>
        <ul className="notice">
          <li>결제 완료 후 영업일 기준 1~2일 내 출고되며, 배송비는 무료입니다.</li>
          <li>주문 후 10분 안에 결제하지 않으면 주문이 자동 취소되고, 잡아 두었던 재고는 다른 고객에게 돌아갑니다.</li>
          <li>단순 변심에 의한 교환·반품은 수령 후 7일 이내 가능하며, 왕복 배송비가 부과됩니다.</li>
          <li>상품 불량 시 수령 후 30일 이내 무상 교환·반품이 가능합니다.</li>
        </ul>
      </details>

      {product.related.length > 0 && (
        <section className="detail-section">
          <h2>같은 카테고리 상품</h2>
          <div className="related">{product.related.map(p => <ProductCard key={p.id} product={p} />)}</div>
        </section>
      )}
    </div>
  )
}
