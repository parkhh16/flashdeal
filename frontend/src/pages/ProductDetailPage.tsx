import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError, type ProductDetail } from '../api'
import { ProductCard } from '../components/ProductCard'
import { ProductImage } from '../components/ProductImage'
import { useApp } from '../context/AppContext'
import { won } from '../format'

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

  if (notFound) return <div className="empty"><p>상품을 찾을 수 없습니다.</p><Link to="/" className="ghost-link">상품 목록으로</Link></div>
  if (!product) return <div className="detail skeleton" aria-busy="true"><div className="sk-img" /><div><div className="sk-line" /><div className="sk-line short" /></div></div>

  const images = product.images.length > 0 ? product.images : [null]
  const maxQty = Math.max(1, Math.min(product.stock, product.perUserLimit ?? 10, 10))
  const soldOut = product.soldOut
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

  return (
    <div className="detail-page">
      <nav className="crumbs" aria-label="현재 위치">
        <Link to="/">홈</Link> › <Link to={`/?category=${product.category}`}>{product.categoryLabel}</Link> ›{' '}
        <Link to={`/?category=${product.category}&sub=${product.subCategory}`}>{product.subCategoryLabel}</Link>
      </nav>

      <div className="detail">
        <div className="gallery">
          <ProductImage src={images[imageIndex]} alt={`${product.name} 이미지 ${imageIndex + 1}`} category={product.category} eager />
          {images.length > 1 && (
            <div className="thumbs">
              {images.map((src, i) => (
                <button key={i} className={i === imageIndex ? 'active' : ''} onClick={() => setImageIndex(i)} aria-label={`이미지 ${i + 1} 보기`}>
                  <ProductImage src={src} alt="" category={product.category} />
                </button>
              ))}
            </div>
          )}
        </div>

        <div className="buy-box">
          <div className="cat">{product.categoryLabel} · {product.subCategoryLabel}</div>
          <h1>{product.name}</h1>
          <p className="muted">{product.description}</p>

          <div className="price-block">
            {product.discountRate > 0 && product.originalPrice && <div className="original">{won(product.originalPrice)}</div>}
            <div className="price-row">
              {product.discountRate > 0 && <span className="discount big">{product.discountRate}%</span>}
              <span className="price big">{won(product.price)}</span>
            </div>
          </div>

          <dl className="facts">
            <dt>재고</dt><dd>{soldOut ? '품절' : `${product.stock}개 남음`}</dd>
            {product.perUserLimit && <><dt>구매 제한</dt><dd>1인 {product.perUserLimit}개</dd></>}
            <dt>배송</dt><dd>무료배송 · 결제 후 1~2일 내 출고</dd>
          </dl>

          <div className="qty-row">
            <span>수량</span>
            <div className="qty" role="group" aria-label="수량 선택">
              <button className="ghost" onClick={() => setQuantity(q => Math.max(1, q - 1))} disabled={quantity <= 1 || blocked} aria-label="수량 감소">−</button>
              <output aria-live="polite">{quantity}</output>
              <button className="ghost" onClick={() => setQuantity(q => Math.min(maxQty, q + 1))} disabled={quantity >= maxQty || blocked} aria-label="수량 증가">+</button>
            </div>
            <strong className="total">{won(product.price * quantity)}</strong>
          </div>

          {session
            ? <button className="buy" onClick={buy} disabled={blocked || ordering}>{buttonLabel}</button>
            : <button className="buy" onClick={() => navigate(`/login?next=${encodeURIComponent(`/products/${product.id}`)}`)}>{buttonLabel}</button>}
          {!session && <p className="muted center">로그인하면 이 페이지로 다시 돌아와요.</p>}
        </div>
      </div>

      <section className="detail-section">
        <h2>상품 설명</h2>
        <p>{product.detail ?? product.description}</p>
      </section>

      {Object.keys(product.specs).length > 0 && (
        <section className="detail-section">
          <h2>상품 정보</h2>
          <table className="spec-table">
            <tbody>
              {Object.entries(product.specs).map(([k, v]) => <tr key={k}><th scope="row">{k}</th><td>{v}</td></tr>)}
            </tbody>
          </table>
        </section>
      )}

      <section className="detail-section">
        <h2>배송 · 교환 · 반품 안내</h2>
        <ul className="notice">
          <li>결제 완료 후 영업일 기준 1~2일 내 출고되며, 배송비는 무료입니다.</li>
          <li>주문 후 10분 안에 결제하지 않으면 주문이 자동 취소되고, 잡아 두었던 재고는 다른 고객에게 돌아갑니다.</li>
          <li>단순 변심에 의한 교환·반품은 수령 후 7일 이내 가능하며, 왕복 배송비가 부과됩니다.</li>
          <li>상품 불량 시 수령 후 30일 이내 무상 교환·반품이 가능합니다.</li>
        </ul>
      </section>

      {product.related.length > 0 && (
        <section className="detail-section">
          <h2>같은 카테고리 상품</h2>
          <div className="grid">{product.related.map(p => <ProductCard key={p.id} product={p} />)}</div>
        </section>
      )}
    </div>
  )
}
