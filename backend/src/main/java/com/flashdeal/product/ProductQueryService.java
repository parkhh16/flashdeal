package com.flashdeal.product;

import com.flashdeal.common.error.BusinessException;
import com.flashdeal.common.error.ErrorCode;
import com.flashdeal.product.ProductDtos.*;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductQueryService {

    private final ProductRepository productRepository;
    private final Clock clock;

    public List<ProductResponse> search(SearchCondition condition) {
        LocalDateTime now = LocalDateTime.now(clock);
        return productRepository.findAll(spec(condition, now)).stream()
                .map(p -> ProductResponse.from(p, now))
                .toList();
    }

    public ProductDetailResponse detail(Long id) {
        LocalDateTime now = LocalDateTime.now(clock);
        Product product = productRepository.findById(id).filter(Product::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        List<ProductResponse> related = productRepository.findAll(
                        spec(new SearchCondition(null, product.getSubCategory(), null, null, null, true, Sort.POPULAR), now)
                                .and((root, q, cb) -> cb.notEqual(root.get("id"), id)),
                        PageRequest.of(0, 4))
                .map(p -> ProductResponse.from(p, now))
                .getContent();
        return new ProductDetailResponse(ProductResponse.from(product, now), product.getDetail(),
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
        return new CategoryTree(total, productRepository.countActiveDeals(LocalDateTime.now(clock)), nodes);
    }

    /**
     * 조건 조합이 많아서(카테고리×특가×가격×품절×정렬) 정적 쿼리 대신 Specification으로 동적 조립한다.
     * 정렬도 DB에서 처리한다: 인기순은 재고 차감과 같이 갱신되는 sold_count로 정렬한다.
     */
    private Specification<Product> spec(SearchCondition c, LocalDateTime now) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.isTrue(root.get("active")));
            if (c.category() != null) where.add(cb.equal(root.get("category"), c.category()));
            if (c.sub() != null) where.add(cb.equal(root.get("subCategory"), c.sub()));
            if (Boolean.TRUE.equals(c.deal())) where.add(cb.greaterThan(root.get("dealEndAt"), now));
            if (c.minPrice() != null) where.add(cb.greaterThanOrEqualTo(root.get("price"), c.minPrice()));
            if (c.maxPrice() != null) where.add(cb.lessThanOrEqualTo(root.get("price"), c.maxPrice()));
            if (c.excludeSoldOut()) where.add(cb.greaterThan(root.get("stock"), 0));
            if (c.keyword() != null && !c.keyword().isBlank()) {
                // 사용자 입력의 % _ 는 와일드카드가 아니라 글자로 취급한다
                String escaped = c.keyword().strip().toLowerCase()
                        .replace("!", "!!").replace("%", "!%").replace("_", "!_");
                String like = "%" + escaped + "%";
                where.add(cb.or(cb.like(cb.lower(root.get("name")), like, '!'),
                        cb.like(cb.lower(root.get("description")), like, '!')));
            }

            // count 쿼리(페이징)에는 정렬을 붙이지 않는다
            if (query != null && !Long.class.equals(query.getResultType())) {
                query.orderBy(orders(c.sort() == null ? Sort.POPULAR : c.sort(), root, cb, now));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    private List<Order> orders(Sort sort, jakarta.persistence.criteria.Root<Product> root,
                               jakarta.persistence.criteria.CriteriaBuilder cb, LocalDateTime now) {
        return switch (sort) {
            case PRICE_ASC -> List.of(cb.asc(root.get("price")), cb.asc(root.get("id")));
            case PRICE_DESC -> List.of(cb.desc(root.get("price")), cb.asc(root.get("id")));
            case POPULAR -> List.of(cb.desc(root.get("soldCount")), cb.asc(root.get("id")));
            case DEADLINE -> {
                // 진행 중인 특가를 마감이 가까운 순서로 먼저, 나머지(일반/종료)는 뒤로
                Expression<Integer> activeFirst = cb.<Integer>selectCase()
                        .when(cb.greaterThan(root.get("dealEndAt"), now), 0)
                        .otherwise(1);
                yield List.of(cb.asc(activeFirst), cb.asc(root.get("dealEndAt")), cb.asc(root.get("id")));
            }
        };
    }
}
