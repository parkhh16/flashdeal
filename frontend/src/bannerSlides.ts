import type { Product } from './api'
import { LOW_STOCK_THRESHOLD } from './format'

/** 자동으로 넘어가는 간격. 3초는 읽기 전에 넘어가서 5초로 한다 */
export const AUTO_MS = 5000

export interface Slide {
  key: string
  /** 이 상품을 왜 보여 주는지. 광고가 아니라 실제 데이터(남은 수량, 할인율, 판매량)에서 나온 이유만 쓴다 */
  label: string
  product: Product
}

/**
 * 홈 배너에 올릴 상품을 이미 받아 둔 목록에서 고른다 (API 변경 없음). 같은 상품은 한 번만 나온다.
 * 판매 중인 상품만 올리고, 이유별로 하나씩: 남은 수량이 적은 상품, 할인율이 가장 높은 상품, 가장 많이 팔린 상품.
 * 3장이 안 채워지면(막 시작해서 판매량이 없을 때 등) 카테고리가 겹치지 않게 다른 상품으로 채운다.
 */
export function buildSlides(products: Product[]): Slide[] {
  const sellable = products.filter(p => !p.soldOut && p.stock > 0)
  const used = new Set<number>()
  const slides: Slide[] = []
  const add = (key: string, label: string, list: Product[]) => {
    const p = list.find(x => !used.has(x.id))
    if (p) {
      used.add(p.id)
      slides.push({ key, label, product: p })
    }
  }
  add('low', '남은 수량이 적어요', sellable.filter(p => p.stock <= LOW_STOCK_THRESHOLD).sort((a, b) => a.stock - b.stock))
  add('sale', '지금 할인 중이에요', sellable.filter(p => p.discountRate > 0).sort((a, b) => b.discountRate - a.discountRate))
  add('hot', '지금 많이 팔려요', sellable.filter(p => p.soldCount > 0).sort((a, b) => b.soldCount - a.soldCount))
  const seen = new Set(slides.map(s => s.product.category))
  for (const p of sellable) {
    if (slides.length >= 3) break
    if (!used.has(p.id) && !seen.has(p.category)) {
      used.add(p.id)
      seen.add(p.category)
      slides.push({ key: `pick-${p.id}`, label: '이런 상품은 어때요', product: p })
    }
  }
  return slides
}
