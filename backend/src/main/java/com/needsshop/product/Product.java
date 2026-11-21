package com.needsshop.product;

import com.needsshop.common.BaseTimeEntity;
import com.needsshop.common.JsonConverters;
import com.needsshop.common.error.BusinessException;
import com.needsshop.common.error.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code @DynamicUpdate}: 변경된 컬럼만 UPDATE 한다. 없으면 관리자가 상품명만 고쳐도 Hibernate가 모든 컬럼(stock 포함)을 덮어써서,
 * 그 사이 주문으로 줄어든 재고를 되돌려 버리는 Lost Update가 생긴다.
 */
@Getter
@Entity
@DynamicUpdate
@Table(name = "product", indexes = {
        @Index(name = "idx_product_category_price", columnList = "category, price"),
        @Index(name = "idx_product_sub_category", columnList = "sub_category")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ProductCategory category;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "sub_category", nullable = false, length = 30)
    private SubCategory subCategory;

    /** 현재 판매가 */
    @Column(nullable = false)
    private long price;

    /** 정가. 할인 중인 상품일 때만 있고, 화면에서 취소선으로 표시한다 */
    private Long originalPrice;

    @Column(nullable = false)
    private int stock;

    /**
     * JPA @Version을 쓰지 않고 수동으로 관리한다.
     * @Version을 붙이면 NAIVE/PESSIMISTIC 전략에도 낙관적 락이 걸려서 전략별 비교가 불가능해지기 때문이다.
     */
    @Column(nullable = false)
    private long version;

    /** 누적 판매 수량. 인기순 정렬에 쓴다 */
    @Column(nullable = false)
    private int soldCount;

    /** 1인 구매 제한 수량. null이면 제한 없음 (리셀 방지용 상품별 설정) */
    private Integer perUserLimit;

    @Column(length = 500)
    private String description;

    @Column(length = 2000)
    private String detail;

    @Convert(converter = JsonConverters.StringMap.class)
    @Column(length = 4000)
    private Map<String, String> specs = new LinkedHashMap<>();

    /** 판매 중지 여부. 삭제 대신 비활성화한다 (과거 주문이 상품을 참조) */
    @Column(nullable = false)
    private boolean active = true;

    /** 첫 번째가 대표 이미지. 정적 리소스 경로(/images/products/...) */
    @Convert(converter = JsonConverters.StringList.class)
    @Column(length = 2000)
    private List<String> images = new ArrayList<>();

    /**
     * 검색용 문자열: 이름, 짧은 설명, 상세 설명, 스펙을 정규화해서 합친 것 ({@link SearchText}).
     * 검색할 때마다 네 컬럼을 이어 붙여 가공하지 않도록 내용이 바뀔 때 미리 만들어 둔다.
     */
    @Column(name = "search_text", length = SearchText.MAX_LENGTH)
    private String searchText;

    /**
     * AI가 상품 정보를 보고 붙인 "손님이 검색창에 칠 만한 말" ({@code ProductTagger}). 검색용 문자열에 함께 들어간다.
     * 검색할 때 AI를 부르지 않는 대신, 뜻이 같은 다른 표현을 상품 쪽에 미리 붙여 둔다
     */
    @Convert(converter = JsonConverters.StringList.class)
    @Column(name = "search_tags", length = 500)
    private List<String> searchTags = new ArrayList<>();

    /** 태그를 만든 시각. null이면 태그를 (다시) 만들어야 한다: 새 상품이거나 이름·설명이 바뀐 상품 */
    @Column(name = "tagged_at")
    private LocalDateTime taggedAt;

    public Product(String name, ProductCategory category, long price, int stock, String description) {
        this(name, SubCategory.defaultOf(category), price, null, stock, description, null, null, null, null);
    }

    @Builder
    public Product(String name, SubCategory subCategory, long price, Long originalPrice, int stock,
                   String description, String detail, Map<String, String> specs, List<String> images,
                   Integer perUserLimit) {
        this.name = name;
        this.subCategory = subCategory;
        this.category = subCategory.getParent();
        this.price = price;
        this.originalPrice = originalPrice;
        this.stock = stock;
        this.description = description;
        this.detail = detail;
        this.specs = specs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(specs);
        this.images = images == null ? new ArrayList<>() : new ArrayList<>(images);
        this.perUserLimit = perUserLimit;
        refreshSearchText();
    }

    /** 엔티티 변경 감지로 차감한다 (NAIVE, PESSIMISTIC 전략에서 사용) */
    public void decreaseStock(int quantity) {
        if (stock < quantity) {
            throw new BusinessException(ErrorCode.OUT_OF_STOCK, "재고가 부족합니다. productId=" + id);
        }
        this.stock -= quantity;
        this.soldCount += quantity;
    }

    public boolean isSoldOut() {
        return stock <= 0;
    }

    /** 할인율(%). 원가가 없거나 판매가가 더 높으면 0 */
    public int discountRate() {
        if (originalPrice == null || originalPrice <= price) return 0;
        return (int) Math.round((originalPrice - price) * 100.0 / originalPrice);
    }

    public String thumbnail() {
        return images.isEmpty() ? null : images.get(0);
    }

    /** 관리자 수정. 재고는 여기서 바꾸지 않는다 (재고는 원자적 UPDATE 전용 API로만 변경) */
    public void update(String name, SubCategory subCategory, long price, Long originalPrice, String description,
                       String detail, Integer perUserLimit) {
        if (price <= 0) throw new BusinessException(ErrorCode.INVALID_INPUT, "가격은 0보다 커야 합니다.");
        if (originalPrice != null && originalPrice < price) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "정가는 판매가보다 작을 수 없습니다.");
        }
        if (perUserLimit != null && perUserLimit < 1) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "1인 구매 제한은 1개 이상이어야 합니다.");
        }
        boolean contentChanged = !Objects.equals(this.name, name) || this.subCategory != subCategory
                || !Objects.equals(this.description, description) || !Objects.equals(this.detail, detail);
        this.name = name;
        this.subCategory = subCategory;
        this.category = subCategory.getParent();
        this.price = price;
        this.originalPrice = originalPrice;
        this.description = description;
        this.detail = detail;
        this.perUserLimit = perUserLimit;
        if (contentChanged) {
            this.taggedAt = null; // 새 내용으로 태그를 다시 만든다. 그때까지는 이전 태그로 검색된다
        }
        refreshSearchText();
    }

    /** 태그를 만들 때 AI에게 보여 준 내용. 저장 직전에 다시 비교해서, 그사이 상품이 고쳐졌으면 저장하지 않는다 */
    public String contentText() {
        return SearchText.build(name, description, detail, specs);
    }

    public void applyTags(List<String> tags, LocalDateTime at) {
        this.searchTags = new ArrayList<>(tags);
        this.taggedAt = at;
        refreshSearchText();
    }

    private void refreshSearchText() {
        this.searchText = SearchText.build(name, description, detail, specs, searchTags);
    }

    public void changeActive(boolean active) {
        this.active = active;
    }

    public void replaceImages(List<String> images) {
        this.images = new ArrayList<>(images);
    }
}
