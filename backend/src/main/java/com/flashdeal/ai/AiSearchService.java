package com.flashdeal.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.flashdeal.product.ProductDtos.ProductResponse;
import com.flashdeal.product.ProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 자연어 상품 검색.
 * - 캐시: 같은 질의를 다시 파싱하지 않는다 (LLM 비용과 지연 절감). Caffeine의 get(key, loader)는
 *   같은 키에 동시 요청이 몰려도 로더를 한 번만 실행한다 → 캐시 스탬피드(동시 LLM 호출 폭주) 방지
 * - 폴백: 키가 없거나, 타임아웃이나 에러가 나면 규칙 기반 파서로 대체한다. 폴백 결과는 캐시하지 않아서 LLM이 복구되면 바로 돌아온다
 * - 재고는 캐시하지 않는다: 초 단위로 바뀌는 값이라 캐시하면 품절 상품이 구매 가능해 보인다. 캐시하는 건 "질의 → 필터" 매핑뿐이다
 */
@Slf4j
@Service
public class AiSearchService {

    public enum ParsedBy {LLM, LLM_CACHED, RULE_FALLBACK}

    public record AiSearchResponse(String query, ParsedBy parsedBy, SearchFilter filter, String fallbackReason,
                                   long parseMillis, List<ProductResponse> products, String originalQuery) {

        /** 오타 교정으로 찾은 결과면, 사용자가 실제로 입력한 검색어를 함께 돌려준다 */
        AiSearchResponse withOriginalQuery(String original) {
            return new AiSearchResponse(query, parsedBy, filter, fallbackReason, parseMillis, products, original);
        }
    }

    private record Parsed(SearchFilter filter, ParsedBy by, String reason) {
    }

    private final ClaudeQueryParser claude;
    private final RuleBasedQueryParser rules;
    private final ProductRepository productRepository;
    private final Clock clock;
    private final Cache<String, Parsed> cache = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();

    private final TypoCorrector typoCorrector;

    public AiSearchService(ClaudeQueryParser claude, RuleBasedQueryParser rules, ProductRepository productRepository,
                           Clock clock, TypoCorrector typoCorrector) {
        this.typoCorrector = typoCorrector;
        this.clock = clock;
        this.claude = claude;
        this.rules = rules;
        this.productRepository = productRepository;
    }

    public AiSearchResponse search(String rawQuery) {
        return search(rawQuery, true);
    }

    /**
     * 검색 순서: ① 질의 그대로 → ② 결과 0건이면 오타 교정 후 재검색 → ③ 그래도 0건이면 키워드를 빼고 조건 완화.
     * 오타 교정은 결과가 없을 때만 하므로 정상 검색에는 영향이 없다.
     *
     * @param allowCorrection false면 오타 교정을 하지 않는다 (사용자가 "원래 검색어로 검색"을 누른 경우)
     */
    @Transactional(readOnly = true)
    public AiSearchResponse search(String rawQuery, boolean allowCorrection) {
        String query = rawQuery.strip().replaceAll("\\s+", " ");
        AiSearchResponse result = searchExact(query);
        if (!result.products().isEmpty() || !allowCorrection) {
            return result.products().isEmpty() ? relax(result) : result;
        }
        Optional<String> corrected = typoCorrector.correct(query);
        if (corrected.isPresent()) {
            AiSearchResponse retry = searchExact(corrected.get());
            if (!retry.products().isEmpty()) {
                log.info("event=AI_SEARCH_CORRECTED from=\"{}\" to=\"{}\" results={}", query, corrected.get(), retry.products().size());
                return retry.withOriginalQuery(query);
            }
        }
        return relax(result);
    }

    /**
     * 키워드까지 넣어서 결과가 없으면 키워드를 빼고 한 번 더 찾는다.
     * 단, 카테고리나 가격 조건이 하나도 없으면 완화하지 않는다 (키워드만 빼면 전체 상품이 나와서 오히려 혼란스럽다)
     */
    private AiSearchResponse relax(AiSearchResponse r) {
        SearchFilter f = r.filter();
        boolean hasOtherCondition = f.category() != null || f.minPrice() != null || f.maxPrice() != null;
        if (f.keyword() == null || !hasOtherCondition) {
            return r;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<ProductResponse> relaxed = productRepository.search(f.category(), f.minPrice(), f.maxPrice(), null)
                .stream().map(p -> ProductResponse.from(p, now)).toList();
        return new AiSearchResponse(r.query(), r.parsedBy(), f, r.fallbackReason(), r.parseMillis(), relaxed, null);
    }

    private AiSearchResponse searchExact(String query) {
        long start = System.nanoTime();

        boolean[] loaded = {false};
        Parsed parsed = cache.get(query.toLowerCase(), key -> {
            loaded[0] = true;
            return parse(query);
        });
        if (parsed.by() == ParsedBy.RULE_FALLBACK) {
            cache.invalidate(query.toLowerCase());
        } else if (!loaded[0]) {
            parsed = new Parsed(parsed.filter(), ParsedBy.LLM_CACHED, null);
        }
        long parseMillis = (System.nanoTime() - start) / 1_000_000;

        SearchFilter f = parsed.filter();
        LocalDateTime now = LocalDateTime.now(clock);
        List<ProductResponse> products = productRepository
                .search(f.category(), f.minPrice(), f.maxPrice(), f.keyword())
                .stream().map(p -> ProductResponse.from(p, now)).toList();
        log.info("event=AI_SEARCH query=\"{}\" by={} filter={} results={} parseMs={}",
                query, parsed.by(), f, products.size(), parseMillis);
        return new AiSearchResponse(query, parsed.by(), f, parsed.reason(), parseMillis, products, null);
    }

    private Parsed parse(String query) {
        if (!claude.isEnabled()) {
            return new Parsed(rules.parse(query), ParsedBy.RULE_FALLBACK, "ANTHROPIC_API_KEY not configured");
        }
        try {
            Optional<SearchFilter> filter = claude.parse(query);
            return filter.map(f -> new Parsed(f, ParsedBy.LLM, null))
                    .orElseGet(() -> new Parsed(rules.parse(query), ParsedBy.RULE_FALLBACK, "LLM returned no usable output"));
        } catch (RuntimeException e) {
            log.warn("event=AI_FALLBACK query=\"{}\" cause={}", query, e.toString());
            return new Parsed(rules.parse(query), ParsedBy.RULE_FALLBACK, e.getClass().getSimpleName());
        }
    }
}
