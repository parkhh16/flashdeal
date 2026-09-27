package com.flashdeal.product;

import com.flashdeal.common.BaseTimeEntity;
import com.flashdeal.common.JsonConverters;
import com.flashdeal.common.error.BusinessException;
import com.flashdeal.common.error.ErrorCode;
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

/**
 * {@code @DynamicUpdate}: 변경된 컬럼만 UPDATE 한다. 없으면 관리자가 상품명만 고쳐도 Hibernate가 모든 컬럼(stock 포함)을 덮어써서,
 * 그 사이 주문으로 줄어든 재고를 되돌려 버리는 Lost Update가 생긴다.
 */
@Getter
@Entity
@DynamicUpdate
@Table(name = "product", indexes = {
        @Index(name = "idx_product_category_price", columnList = "category, price"),
        @Index(name = "idx_product_sub_category", columnList = "sub_category"),
        @Index(name = "idx_product_deal_end_at", columnList = "deal_end_at")
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

    /** 현재 판매가 (특가 상품이면 특가) */
    @Column(nullable = false)
    private long price;

    /** 정가. 특가 상품일 때 취소선으로 표시한다 */
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

    @Column(name = "deal_start_at")
    private LocalDateTime dealStartAt;

    @Column(name = "deal_end_at")
    private LocalDateTime dealEndAt;

    /** 특가 한정 수량 (재고 진행바의 분모) */
    private Integer dealQuantity;

    /** 1인 구매 제한 수량. null이면 제한 없음 */
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

    public Product(String name, ProductCategory category, long price, int stock, String description) {
        this(name, SubCategory.defaultOf(category), price, null, stock, description, null, null,
                null, null, null, null, null);
    }

    @Builder
    public Product(String name, SubCategory subCategory, long price, Long originalPrice, int stock,
                   String description, String detail, Map<String, String> specs, List<String> images,
                   LocalDateTime dealStartAt, LocalDateTime dealEndAt, Integer dealQuantity, Integer perUserLimit) {
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
        this.dealStartAt = dealStartAt;
        this.dealEndAt = dealEndAt;
        this.dealQuantity = dealQuantity;
        this.perUserLimit = perUserLimit;
    }

    /** 엔티티 변경 감지로 차감한다 (NAIVE, PESSIMISTIC 전략에서 사용) */
    public void decreaseStock(int quantity) {
        if (stock < quantity) {
            throw new BusinessException(ErrorCode.OUT_OF_STOCK, "재고가 부족합니다. productId=" + id);
        }
        this.stock -= quantity;
        this.soldCount += quantity;
    }

    public boolean isDeal() {
        return dealEndAt != null;
    }

    public DealStatus dealStatus(LocalDateTime now) {
        if (!isDeal()) return DealStatus.NONE;
        if (dealStartAt != null && now.isBefore(dealStartAt)) return DealStatus.UPCOMING;
        if (now.isAfter(dealEndAt)) return DealStatus.ENDED;
        if (stock <= 0) return DealStatus.SOLD_OUT;
        return DealStatus.ONGOING;
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
                       String detail, LocalDateTime dealStartAt, LocalDateTime dealEndAt, Integer dealQuantity,
                       Integer perUserLimit) {
        if (price <= 0) throw new BusinessException(ErrorCode.INVALID_INPUT, "가격은 0보다 커야 합니다.");
        if (originalPrice != null && originalPrice < price) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "정가는 판매가보다 작을 수 없습니다.");
        }
        if (dealEndAt != null && dealStartAt != null && !dealEndAt.isAfter(dealStartAt)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "특가 종료 시각은 시작 시각 이후여야 합니다.");
        }
        if (perUserLimit != null && perUserLimit < 1) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "1인 구매 제한은 1개 이상이어야 합니다.");
        }
        this.name = name;
        this.subCategory = subCategory;
        this.category = subCategory.getParent();
        this.price = price;
        this.originalPrice = originalPrice;
        this.description = description;
        this.detail = detail;
        this.dealStartAt = dealStartAt;
        this.dealEndAt = dealEndAt;
        this.dealQuantity = dealQuantity;
        this.perUserLimit = perUserLimit;
    }

    public void changeActive(boolean active) {
        this.active = active;
    }

    public void replaceImages(List<String> images) {
        this.images = new ArrayList<>(images);
    }
}
