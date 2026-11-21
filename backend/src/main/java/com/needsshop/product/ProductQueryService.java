package com.needsshop.product;

import com.needsshop.common.error.BusinessException;
import com.needsshop.common.error.ErrorCode;
import com.needsshop.product.ProductDtos.*;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductQueryService {

    private final ProductRepository productRepository;

    public List<ProductResponse> search(SearchCondition condition) {
        return productRepository.findAll(spec(condition)).stream()
                .map(ProductResponse::from)
                .toList();
    }

    /**
     * 자연어 검색의 단어 조건.
     *
     * @param required 반드시 있어야 하는 단어 (검색어에 쓴 상품 종류: "헤드셋", "허브")
     * @param words    나머지 단어 ("무선", "조용한", "사무실에서")
     * @param excludes 있으면 안 되는 단어 ("게이밍 말고")
     */
    public record WordQuery(ProductCategory category, Long minPrice, Long maxPrice,
                            List<String> required, List<String> words, List<String> excludes) {

        public WordQuery withoutWords() {
            return new WordQuery(category, minPrice, maxPrice, List.of(), List.of(), excludes);
        }
    }

    /** 단어가 모두 맞는 상품. 한 글자 단어("눈")는 다른 단어가 있으면 필수로 보지 않는다 */
    public List<ProductResponse> findAllWords(WordQuery q) {
        List<String> strong = q.words().stream().filter(w -> !SearchText.isWeak(w)).toList();
        List<String> mustMatch = strong.isEmpty() ? q.words() : strong;
        return productRepository.findAll(scope(q).and(allOf(mustMatch)), PRICE_ASC).stream()
                .map(ProductResponse::from)
                .toList();
    }

    /**
     * 단어가 모두 맞는 상품이 없을 때: 가장 많은 단어가 맞는 상품들. DB에서는 하나라도 맞는 상품만 걸러 오고,
     * 몇 개가 맞는지는 같은 규칙({@link SearchText#matchesAny})으로 세서 가장 많이 맞은 상품만 남긴다.
     * 하나도 안 맞으면 상품 종류(required)만 맞는 상품을 돌려준다.
     */
    public List<ProductResponse> findBestWords(WordQuery q) {
        List<Product> candidates = q.words().isEmpty() ? List.of()
                : productRepository.findAll(scope(q).and(anyOf(q.words())), PRICE_ASC);
        if (candidates.isEmpty()) {
            return q.required().isEmpty() ? List.of() : findAllWords(new WordQuery(q.category(), q.minPrice(),
                    q.maxPrice(), q.required(), List.of(), q.excludes()));
        }
        Map<Product, Long> scores = new LinkedHashMap<>();
        candidates.forEach(p -> scores.put(p, q.words().stream().filter(w -> SearchText.matchesAny(p.getSearchText(), w)).count()));
        long best = Collections.max(scores.values());
        return scores.entrySet().stream()
                .filter(e -> e.getValue() == best)
                .map(e -> ProductResponse.from(e.getKey()))
                .toList();
    }

    private static final org.springframework.data.domain.Sort PRICE_ASC =
            org.springframework.data.domain.Sort.by("price", "id");

    /** 판매 중 + 카테고리·가격 + 필수 단어 + 제외 단어 */
    private Specification<Product> scope(WordQuery q) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.isTrue(root.get("active")));
            if (q.category() != null) where.add(cb.equal(root.get("category"), q.category()));
            if (q.minPrice() != null) where.add(cb.greaterThanOrEqualTo(root.get("price"), q.minPrice()));
            if (q.maxPrice() != null) where.add(cb.lessThanOrEqualTo(root.get("price"), q.maxPrice()));
            q.required().forEach(w -> where.add(word(w, root, cb)));
            q.excludes().forEach(w -> where.add(cb.not(word(w, root, cb))));
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    private static Specification<Product> allOf(List<String> words) {
        return (root, query, cb) -> cb.and(words.stream().map(w -> word(w, root, cb)).toArray(Predicate[]::new));
    }

    private static Specification<Product> anyOf(List<String> words) {
        return (root, query, cb) -> cb.or(words.stream().map(w -> word(w, root, cb)).toArray(Predicate[]::new));
    }

    /** 단어 하나(표기가 다른 같은 단어 포함)가 검색용 문자열에 있는지. 규칙은 {@link SearchText} */
    private static Predicate word(String word, jakarta.persistence.criteria.Root<Product> root,
                                  jakarta.persistence.criteria.CriteriaBuilder cb) {
        jakarta.persistence.criteria.Expression<String> text = root.get("searchText");
        jakarta.persistence.criteria.Expression<String> noSpace =
                cb.function("replace", String.class, text, cb.literal(" "), cb.literal(""));
        List<Predicate> any = new ArrayList<>();
        for (String v : SearchText.variants(word)) {
            SearchText.WordPattern p = SearchText.pattern(v);
            if (p.anywhere() != null) any.add(cb.like(noSpace, p.anywhere(), '!'));
            if (p.wordStart() != null) any.add(cb.like(text, p.wordStart(), '!'));
        }
        return cb.or(any.toArray(Predicate[]::new));
    }

    public ProductDetailResponse detail(Long id) {
        Product product = productRepository.findById(id).filter(Product::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        List<ProductResponse> related = productRepository.findAll(
                        spec(new SearchCondition(null, product.getSubCategory(), null, null, true, Sort.POPULAR))
                                .and((root, q, cb) -> cb.notEqual(root.get("id"), id)),
                        PageRequest.of(0, 4))
                .map(ProductResponse::from)
                .getContent();
        return new ProductDetailResponse(ProductResponse.from(product), product.getDetail(),
                product.getSpecs(), product.getImages(), related);
    }

    /** 사이드바 트리. 상품이 0개인 소분류도 보여줘야 하므로 enum 전체를 기준으로 개수를 채운다 */
    public CategoryTree categories() {
        Map<SubCategory, Long> counts = productRepository.countBySubCategory().stream()
                .collect(Collectors.toMap(ProductRepository.SubCategoryCount::getSubCategory,
                        ProductRepository.SubCategoryCount::getCount));
        List<CategoryNode> nodes = Arrays.stream(ProductCategory.values()).map(category -> {
            List<CategoryNode> children = Arrays.stream(SubCategory.values())
                    .filter(s -> s.getParent() == category)
                    .map(s -> new CategoryNode(s.name(), s.getLabel(), counts.getOrDefault(s, 0L), List.of()))
                    .toList();
            long sum = children.stream().mapToLong(CategoryNode::count).sum();
            return new CategoryNode(category.name(), category.getLabel(), sum, children);
        }).toList();
        long total = nodes.stream().mapToLong(CategoryNode::count).sum();
        return new CategoryTree(total, nodes);
    }

    /**
     * 조건 조합이 많아서(카테고리×가격×품절×키워드×정렬) 정적 쿼리 대신 Specification으로 동적 조립한다.
     * 정렬도 DB에서 처리한다: 인기순은 재고 차감과 같이 갱신되는 sold_count로 정렬한다.
     */
    private Specification<Product> spec(SearchCondition c) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.isTrue(root.get("active")));
            if (c.category() != null) where.add(cb.equal(root.get("category"), c.category()));
            if (c.sub() != null) where.add(cb.equal(root.get("subCategory"), c.sub()));
            if (c.minPrice() != null) where.add(cb.greaterThanOrEqualTo(root.get("price"), c.minPrice()));
            if (c.maxPrice() != null) where.add(cb.lessThanOrEqualTo(root.get("price"), c.maxPrice()));
            if (c.excludeSoldOut()) where.add(cb.greaterThan(root.get("stock"), 0));
            if (c.keyword() != null && !c.keyword().isBlank()) {
                // 단어별로 이름·설명·상세·스펙에서 찾는다 (띄어쓰기·조사 차이 허용, 규칙은 SearchText)
                SearchText.words(c.keyword()).forEach(w -> where.add(word(w, root, cb)));
            }

            // count 쿼리(페이징)에는 정렬을 붙이지 않는다
            if (query != null && !Long.class.equals(query.getResultType())) {
                query.orderBy(orders(c.sort() == null ? Sort.POPULAR : c.sort(), root, cb));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    private List<Order> orders(Sort sort, jakarta.persistence.criteria.Root<Product> root,
                               jakarta.persistence.criteria.CriteriaBuilder cb) {
        return switch (sort) {
            case PRICE_ASC -> List.of(cb.asc(root.get("price")), cb.asc(root.get("id")));
            case PRICE_DESC -> List.of(cb.desc(root.get("price")), cb.asc(root.get("id")));
            case POPULAR -> List.of(cb.desc(root.get("soldCount")), cb.asc(root.get("id")));
        };
    }
}
