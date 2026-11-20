package com.needsshop.ai;

import com.needsshop.product.ProductCategory;

import java.util.List;

/**
 * 자연어 질의를 파싱한 결과. LLM이 만들든 규칙 기반 파서가 만들든 같은 형태여서 검색 로직은 하나로 유지된다.
 *
 * @param excludes 결과에서 뺄 단어 ("게이밍 말고" → 게이밍)
 */
public record SearchFilter(String keyword, ProductCategory category, Long minPrice, Long maxPrice, List<String> excludes) {

    public static final long PRICE_CAP = 100_000_000L;

    public SearchFilter {
        excludes = excludes == null ? List.of() : List.copyOf(excludes);
    }

    public SearchFilter(String keyword, ProductCategory category, Long minPrice, Long maxPrice) {
        this(keyword, category, minPrice, maxPrice, List.of());
    }

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
        List<String> ex = excludes.stream().map(String::strip).filter(s -> !s.isEmpty()).limit(3).toList();
        return new SearchFilter(kw, category, min, max, ex);
    }

    private static boolean valid(Long price) {
        return price != null && price > 0 && price <= PRICE_CAP;
    }
}
