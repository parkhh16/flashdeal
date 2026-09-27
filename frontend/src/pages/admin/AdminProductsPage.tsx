import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { adminApi, api, type AdminProduct, type CategoryTree, type ProductForm } from '../../api'
import { DealBadge } from '../../components/ProductCard'
import { ProductImage } from '../../components/ProductImage'
import { useApp } from '../../context/AppContext'
import { won } from '../../format'
import { useNow } from '../../hooks'

const toInput = (iso: string | null) => (iso ? iso.slice(0, 16) : '')
const fromInput = (v: string) => (v ? `${v}:00` : null)
const numOrNull = (v: string) => (v === '' ? null : Number(v))

function emptyForm(): ProductForm {
  return { name: '', subCategory: 'KEYBOARD_MECHANICAL', price: 10000, originalPrice: null, stock: 100, description: '', detail: '',
    dealStartAt: null, dealEndAt: null, dealQuantity: null, perUserLimit: null }
}

function ProductEditor({ product, tree, onClose, onSaved }: {
  product: AdminProduct | null; tree: CategoryTree | null; onClose: () => void; onSaved: () => void
}) {
  const { toast, fail } = useApp()
  const [form, setForm] = useState<ProductForm>(() => product ? {
    name: product.name, subCategory: product.subCategory, price: product.price, originalPrice: product.originalPrice,
    stock: null, description: product.description ?? '', detail: product.detail ?? '',
    dealStartAt: product.dealStartAt, dealEndAt: product.dealEndAt, dealQuantity: product.dealQuantity, perUserLimit: product.perUserLimit,
  } : emptyForm())
  const [deal, setDeal] = useState(!!product?.dealEndAt)
  const [saving, setSaving] = useState(false)
  const set = <K extends keyof ProductForm>(k: K, v: ProductForm[K]) => setForm(f => ({ ...f, [k]: v }))

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setSaving(true)
    const body: ProductForm = deal ? form : { ...form, dealStartAt: null, dealEndAt: null, dealQuantity: null }
    try {
      if (product) await adminApi.updateProduct(product.id, body)
      else await adminApi.createProduct(body)
      toast(product ? '상품을 수정했습니다.' : '상품을 등록했습니다.', 'success')
      onSaved()
    } catch (err) {
      fail(err)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <form className="modal" onClick={e => e.stopPropagation()} onSubmit={submit} aria-label="상품 편집">
        <div className="modal-head">
          <h3>{product ? `상품 수정 · #${product.id}` : '새 상품 등록'}</h3>
          <button type="button" className="icon-btn" onClick={onClose} aria-label="닫기">✕</button>
        </div>
        <div className="form-grid">
          <label className="span2">상품명<input required maxLength={100} value={form.name} onChange={e => set('name', e.target.value)} /></label>
          <label>카테고리
            <select value={form.subCategory} onChange={e => set('subCategory', e.target.value)}>
              {tree?.categories.map(c => (
                <optgroup key={c.code} label={c.label}>
                  {c.children.map(s => <option key={s.code} value={s.code}>{c.label} › {s.label}</option>)}
                </optgroup>
              ))}
            </select>
          </label>
          <label>판매가 (원)<input type="number" required min={1} value={form.price} onChange={e => set('price', Number(e.target.value))} /></label>
          <label>정가 (원, 할인 표시용)<input type="number" min={1} value={form.originalPrice ?? ''} onChange={e => set('originalPrice', numOrNull(e.target.value))} placeholder="없으면 비워두기" /></label>
          {!product && <label>초기 재고<input type="number" min={0} value={form.stock ?? 0} onChange={e => set('stock', Number(e.target.value))} /></label>}
          <label>1인 구매 제한<input type="number" min={1} value={form.perUserLimit ?? ''} onChange={e => set('perUserLimit', numOrNull(e.target.value))} placeholder="제한 없음" /></label>
          <label className="span2">한 줄 설명<input maxLength={500} value={form.description} onChange={e => set('description', e.target.value)} /></label>
          <label className="span2">상세 설명<textarea rows={3} maxLength={2000} value={form.detail} onChange={e => set('detail', e.target.value)} /></label>
          <label className="check span2"><input type="checkbox" checked={deal} onChange={e => setDeal(e.target.checked)} /> 특가 상품으로 설정 (기간 한정)</label>
          {deal && <>
            <label>오픈 시각<input type="datetime-local" value={toInput(form.dealStartAt)} onChange={e => set('dealStartAt', fromInput(e.target.value))} /></label>
            <label>종료 시각<input type="datetime-local" required value={toInput(form.dealEndAt)} onChange={e => set('dealEndAt', fromInput(e.target.value))} /></label>
            <label>한정 수량 (진행바 기준)<input type="number" min={1} value={form.dealQuantity ?? ''} onChange={e => set('dealQuantity', numOrNull(e.target.value))} /></label>
          </>}
        </div>
        {product && <p className="muted">재고는 목록의 "재고" 칸에서 따로 조정합니다. 수정 중에 들어온 주문의 재고 차감을 덮어쓰지 않기 위해서입니다.</p>}
        <div className="modal-foot">
          <button type="button" className="ghost" onClick={onClose}>취소</button>
          <button type="submit" disabled={saving}>{saving ? '저장 중…' : '저장'}</button>
        </div>
      </form>
    </div>
  )
}

/**
 * 재고는 "몇 개로 바꾼다"가 아니라 "몇 개 들어왔다/나갔다"로 조정한다.
 * 결제 대기 주문이 쥔 수량(held)은 가용 재고에서 이미 빠져 있고 나중에 돌아올 수 있어서,
 * 가용 재고를 덮어쓰면 그 수량이 돌아올 때 실물보다 많아진다.
 */
function StockCell({ product, onSaved }: { product: AdminProduct; onSaved: () => void }) {
  const { toast, fail } = useApp()
  const [delta, setDelta] = useState('')
  const [busy, setBusy] = useState(false)
  const n = Number(delta)
  const valid = delta !== '' && Number.isInteger(n) && n !== 0

  const save = async () => {
    setBusy(true)
    try {
      const { data } = await adminApi.adjustStock(product.id, n, n > 0 ? '관리자 입고' : '관리자 출고')
      toast(`'${product.name}' ${n > 0 ? `${n}개 입고` : `${-n}개 출고`} → 가용 재고 ${data.stock}개`, 'success')
      setDelta('')
      onSaved()
    } catch (e) { fail(e) } finally { setBusy(false) }
  }

  return (
    <div className="stock-cell">
      <div>
        <strong className="num">{product.stock}</strong>
        {product.held > 0 && <div className="muted" title="결제 대기·결제 확인 중인 주문이 쥐고 있는 수량. 결제되면 판매로, 만료·취소되면 가용 재고로 돌아간다">+ 결제 대기 {product.held}</div>}
      </div>
      <input type="number" step={1} placeholder="+입고 / -출고" value={delta} onChange={e => setDelta(e.target.value)}
             aria-label={`${product.name} 재고 증감 수량`} onKeyDown={e => { if (e.key === 'Enter' && valid && !busy) save() }} />
      {valid && <button className="small" onClick={save} disabled={busy}>{n > 0 ? '입고' : '출고'}</button>}
    </div>
  )
}

export function AdminProductsPage() {
  const { toast, fail } = useApp()
  const [products, setProducts] = useState<AdminProduct[] | null>(null)
  const [tree, setTree] = useState<CategoryTree | null>(null)
  const [editing, setEditing] = useState<AdminProduct | null | 'new'>(null)
  const [query, setQuery] = useState('')
  const now = useNow()

  const load = () => adminApi.products().then(r => setProducts(r.data)).catch(fail)
  useEffect(() => { load(); api.categories().then(r => setTree(r.data)).catch(() => undefined) }, [])

  const toggle = async (p: AdminProduct) => {
    try {
      await adminApi.setActive(p.id, !p.active)
      toast(p.active ? `'${p.name}' 판매를 중지했습니다.` : `'${p.name}' 판매를 재개했습니다.`, 'success')
      load()
    } catch (e) { fail(e) }
  }

  const visible = products?.filter(p => !query || p.name.includes(query))

  return (
    <div className="admin-body">
      <div className="filter-bar">
        <input placeholder="상품명 검색" value={query} onChange={e => setQuery(e.target.value)} aria-label="상품명 검색" />
        <span className="muted">{visible?.length ?? 0}개</span>
        <button className="push-right" onClick={() => setEditing('new')}>+ 새 상품</button>
      </div>
      <div className="table-wrap">
        <table className="data-table">
          <thead>
            <tr><th></th><th>상품</th><th>가격</th><th title="가용 재고 (결제 대기 주문이 쥔 수량은 따로 표시)">재고 · 조정</th><th>판매량</th><th>특가</th><th>판매</th><th></th></tr>
          </thead>
          <tbody>
            {visible?.map(p => (
              <tr key={p.id} className={p.active ? '' : 'inactive'}>
                <td className="thumb-cell"><ProductImage src={p.thumbnail} alt="" category={p.category} /></td>
                <td>
                  <div className="cell-title">{p.active ? <Link to={`/products/${p.id}`}>{p.name}</Link> : p.name}</div>
                  <div className="muted">#{p.id} · {p.categoryLabel} › {p.subCategoryLabel}{p.perUserLimit ? ` · 1인 ${p.perUserLimit}개` : ''}</div>
                </td>
                <td className="nowrap">
                  {won(p.price)}
                  {p.discountRate > 0 && <div className="muted">정가 {won(p.originalPrice!)} · {p.discountRate}%↓</div>}
                </td>
                <td><StockCell product={p} onSaved={load} /></td>
                <td className="num">{p.soldCount}</td>
                <td>{p.dealStatus === 'NONE' ? <span className="muted">–</span> : <DealBadge product={p} now={now} />}</td>
                <td>
                  <button className={`switch ${p.active ? 'on' : ''}`} role="switch" aria-checked={p.active} onClick={() => toggle(p)}
                          aria-label={`${p.name} 판매 ${p.active ? '중지' : '재개'}`}>
                    <span />{p.active ? '판매 중' : '중지'}
                  </button>
                </td>
                <td><button className="ghost small" onClick={() => setEditing(p)}>수정</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {editing && (
        <ProductEditor product={editing === 'new' ? null : editing} tree={tree} onClose={() => setEditing(null)}
                       onSaved={() => { setEditing(null); load() }} />
      )}
    </div>
  )
}
