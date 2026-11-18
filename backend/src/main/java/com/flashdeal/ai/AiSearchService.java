package com.flashdeal.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.flashdeal.product.ProductDtos.ProductResponse;
import com.flashdeal.product.ProductQueryService;
import com.flashdeal.product.ProductQueryService.WordQuery;
import com.flashdeal.product.SearchText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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

    /**
     * 결과가 어떻게 나왔는지. 화면은 이걸 보고 "일부 단어만 맞는 상품이에요"처럼 알려 준다
     * (완화한 결과를 정확한 결과처럼 보여 주면 검색이 잘못돼도 알아채기 어렵다).
     */
    public enum Match {ALL_WORDS, SOME_WORDS, RELAXED, NONE}

    public record AiSearchResponse(String query, ParsedBy parsedBy, SearchFilter filter, String fallbackReason,
                                   long parseMillis, List<ProductResponse> products, String originalQuery, Match match) {

        /** 오타 교정으로 찾은 결과면, 사용자가 실제로 입력한 검색어를 함께 돌려준다 */
        AiSearchResponse withOriginalQuery(String original) {
            return new AiSearchResponse(query, parsedBy, filter, fallbackReason, parseMillis, products, original, match);
        }

        AiSearchResponse with(List<ProductResponse> products, Match match) {
            return new AiSearchResponse(query, parsedBy, filter, fallbackReason, parseMillis, products, originalQuery, match);
        }
    }

    private record Parsed(SearchFilter filter, ParsedBy by, String reason) {
    }

    private final QueryParser llm;
    private final RuleBasedQueryParser rules;
    private final ProductQueryService products;
    private final TypoCorrector typoCorrector;
    private final Cache<String, Parsed> cache = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();

    public AiSearchService(QueryParser llm, RuleBasedQueryParser rules, ProductQueryService products,
                           TypoCorrector typoCorrector) {
        this.llm = llm;
        this.rules = rules;
        this.products = products;
        this.typoCorrector = typoCorrector;
    }

    public AiSearchResponse search(String rawQuery) {
        return search(rawQuery, true);
    }

    /**
     * 검색 순서. 앞 단계에서 결과가 나오면 거기서 끝낸다.
     * <ol>
     *   <li>검색어의 단어가 모두 맞는 상품</li>
     *   <li>오타를 고친 검색어로 1번 ("마으수" → "마우스")</li>
     *   <li>단어가 가장 많이 맞는 상품 ("밤에 조용한 키보드"에서 "조용한"만 맞는 상품)</li>
     *   <li>단어를 빼고 카테고리·가격 조건만으로. 다른 조건이 없으면 하지 않는다 (전체 상품이 나와서 오히려 혼란스럽다)</li>
     * </ol>
     *
     * @param allowCorrection false면 오타 교정을 하지 않는다 (사용자가 "원래 검색어로 검색"을 누른 경우)
     */
    @Transactional(readOnly = true)
    public AiSearchResponse search(String rawQuery, boolean allowCorrection) {
        String query = rawQuery.strip().replaceAll("\\s+", " ");
        AiSearchResponse exact = searchAllWords(query);
        if (!exact.products().isEmpty()) {
            return exact;
        }
        if (allowCorrection) {
            Optional<String> corrected = typoCorrector.correct(query);
            if (corrected.isPresent()) {
                AiSearchResponse retry = searchAllWords(corrected.get());
                if (!retry.products().isEmpty()) {
                    log.info("event=AI_SEARCH_CORRECTED from=\"{}\" to=\"{}\" results={}", query, corrected.get(), retry.products().size());
                    return retry.withOriginalQuery(query);
                }
            }
        }

        WordQuery words = toWordQuery(exact.filter());
        List<ProductResponse> best = products.findBestWords(words);
        if (!best.isEmpty()) {
            log.info("event=AI_SEARCH_PARTIAL query=\"{}\" results={}", query, best.size());
            return exact.with(best, Match.SOME_WORDS);
        }

        SearchFilter f = exact.filter();
        boolean hasOtherCondition = f.category() != null || f.minPrice() != null || f.maxPrice() != null;
        if (f.keyword() == null || !hasOtherCondition) {
            return exact.with(List.of(), Match.NONE);
        }
        List<ProductResponse> relaxed = products.findAllWords(words.withoutWords());
        log.info("event=AI_SEARCH_RELAXED query=\"{}\" results={}", query, relaxed.size());
        return exact.with(relaxed, relaxed.isEmpty() ? Match.NONE : Match.RELAXED);
    }

    private AiSearchResponse searchAllWords(String query) {
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
        List<ProductResponse> found = products.findAllWords(toWordQuery(f));
        log.info("event=AI_SEARCH query=\"{}\" by={} filter={} results={} parseMs={}",
                query, parsed.by(), f, found.size(), parseMillis);
        return new AiSearchResponse(query, parsed.by(), f, parsed.reason(), parseMillis, found, null,
                found.isEmpty() ? Match.NONE : Match.ALL_WORDS);
    }

    /**
     * 키워드를 단어로 나누고, 상품 종류 단어("헤드셋", "허브")는 반드시 있어야 하는 단어로 분리한다.
     * 종류 단어가 여럿이면 맨 뒤의 것만 필수다 ("헤드셋 거치대"는 거치대를 찾는 것이고, 헤드셋은 꾸미는 말)
     */
    static WordQuery toWordQuery(SearchFilter f) {
        List<String> all = SearchText.words(f.keyword());
        String head = all.stream().filter(RuleBasedQueryParser::isRequiredWord).reduce((a, b) -> b).orElse(null);
        List<String> required = head == null ? List.of() : List.of(head);
        List<String> words = all.stream().filter(w -> !w.equals(head)).distinct().toList();
        List<String> excludes = f.excludes().stream().flatMap(e -> SearchText.words(e).stream()).toList();
        return new WordQuery(f.category(), f.minPrice(), f.maxPrice(), required, words, excludes);
    }

    private Parsed parse(String query) {
        if (!llm.isEnabled()) {
            return new Parsed(rules.parse(query), ParsedBy.RULE_FALLBACK, "GEMINI_API_KEY not configured");
        }
        try {
            Optional<SearchFilter> filter = llm.parse(query);
            return filter.map(f -> new Parsed(f, ParsedBy.LLM, null))
                    .orElseGet(() -> new Parsed(rules.parse(query), ParsedBy.RULE_FALLBACK, "LLM returned no usable output"));
        } catch (RuntimeException e) {
            log.warn("event=AI_FALLBACK query=\"{}\" cause={}", query, e.toString());
            return new Parsed(rules.parse(query), ParsedBy.RULE_FALLBACK, e.getClass().getSimpleName());
        }
    }
}
