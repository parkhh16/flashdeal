package com.flashdeal.ai;

import com.flashdeal.product.ProductCategory;

/**
 * 자연어 질의를 파싱한 결과. LLM이 만들든 규칙 기반 파서가 만들든 같은 형태여서 검색 로직은 하나로 유지된다.
 */
public record SearchFilter(String keyword, ProductCategory category, Long minPrice, Long maxPrice) {

    public static final long PRICE_CAP = 100_000_000L;

    /** LLM 출력을 믿지 않는다. 범위를 벗어난 값은 버리고, min > max면 서로 바꾼다 */
    public SearchFilter sanitized() {
        Long min = valid(minPrice) ? minPrice : null;
        Long max = valid(maxPrice) ? maxPrice : null;
        if (min != null && max != null && min > max) {
            Long tmp = min;
            min = max;
            max = tmp;
        }
        String kw = keyword == null || keyword.isBlank() ? null : keyword.strip();
        if (kw != null && kw.length() > 30) {
            kw = kw.substring(0, 30);
        }
        return new SearchFilter(kw, category, min, max);
    }

    private static boolean valid(Long price) {
        return price != null && price > 0 && price <= PRICE_CAP;
    }
}
