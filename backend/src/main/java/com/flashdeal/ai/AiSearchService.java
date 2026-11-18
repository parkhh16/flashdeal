package com.flashdeal.ai;

import com.flashdeal.product.ProductDtos.ProductResponse;
import com.flashdeal.product.ProductQueryService;
import com.flashdeal.product.ProductQueryService.WordQuery;
import com.flashdeal.product.SearchText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 자연어 상품 검색. 검색어 해석은 규칙 기반 파서가 하고, 검색할 때 LLM은 부르지 않는다.
 *
 * <p>처음에는 검색마다 Gemini로 검색어를 해석했지만, 응답이 0.8~2.5초에서 7.5초까지 느려지면서
 * 매 검색이 타임아웃(5초)을 기다린 뒤 규칙 기반으로 넘어가게 됐다. 그래서 AI는 손님이 기다리지 않는 쪽으로 옮겼다:
 * 상품을 등록할 때 "손님이 쓸 만한 말"을 태그로 붙여 두고({@link ProductTagger}), 검색은 그 태그까지 단어로 찾는다.
 */
@Slf4j
@Service
public class AiSearchService {

    /**
     * 결과가 어떻게 나왔는지. 화면은 이걸 보고 "일부 단어만 맞는 상품이에요"처럼 알려 준다
     * (완화한 결과를 정확한 결과처럼 보여 주면 검색이 잘못돼도 알아채기 어렵다).
     */
    public enum Match {ALL_WORDS, SOME_WORDS, RELAXED, NONE}

    public record AiSearchResponse(String query, SearchFilter filter, List<ProductResponse> products,
                                   String originalQuery, Match match) {

        /** 오타 교정으로 찾은 결과면, 사용자가 실제로 입력한 검색어를 함께 돌려준다 */
        AiSearchResponse withOriginalQuery(String original) {
            return new AiSearchResponse(query, filter, products, original, match);
        }

        AiSearchResponse with(List<ProductResponse> products, Match match) {
            return new AiSearchResponse(query, filter, products, originalQuery, match);
        }
    }

    private final RuleBasedQueryParser rules;
    private final ProductQueryService products;
    private final TypoCorrector typoCorrector;

    public AiSearchService(RuleBasedQueryParser rules, ProductQueryService products, TypoCorrector typoCorrector) {
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
        SearchFilter f = rules.parse(query);
        List<ProductResponse> found = products.findAllWords(toWordQuery(f));
        log.info("event=AI_SEARCH query=\"{}\" filter={} results={}", query, f, found.size());
        return new AiSearchResponse(query, f, found, null, found.isEmpty() ? Match.NONE : Match.ALL_WORDS);
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
}
