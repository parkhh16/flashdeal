package com.flashdeal.product;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class ProductDtos {

    private ProductDtos() {
    }

    public enum Sort {POPULAR, PRICE_ASC, PRICE_DESC, DEADLINE}

    public record SearchCondition(ProductCategory category, SubCategory sub, Boolean deal, Long minPrice,
                                  Long maxPrice, boolean excludeSoldOut, Sort sort, String keyword) {

        public SearchCondition(ProductCategory category, SubCategory sub, Boolean deal, Long minPrice, Long maxPrice,
                               boolean excludeSoldOut, Sort sort) {
            this(category, sub, deal, minPrice, maxPrice, excludeSoldOut, sort, null);
        }
    }

    /** 목록용. 상세 설명/스펙처럼 무거운 필드는 빼서 응답 크기를 줄인다 */
    public record ProductResponse(Long id, String name, ProductCategory category, String categoryLabel,
                                  SubCategory subCategory, String subCategoryLabel, long price, Long originalPrice,
                                  int discountRate, int stock, int soldCount, String description, String thumbnail,
                                  DealStatus dealStatus, LocalDateTime dealStartAt, LocalDateTime dealEndAt,
                                  Integer dealQuantity, Integer perUserLimit) {

        public static ProductResponse from(Product p, LocalDateTime now) {
            return new ProductResponse(p.getId(), p.getName(), p.getCategory(), p.getCategory().getLabel(),
                    p.getSubCategory(), p.getSubCategory().getLabel(), p.getPrice(), p.getOriginalPrice(),
                    p.discountRate(), p.getStock(), p.getSoldCount(), p.getDescription(), p.thumbnail(),
                    p.dealStatus(now), p.getDealStartAt(), p.getDealEndAt(), p.getDealQuantity(), p.getPerUserLimit());
        }
    }

    /** 목록 필드를 평탄화(@JsonUnwrapped)해서 기존 GET /api/products/{id} 응답 형태(stock 등 최상위 필드)를 유지한다 */
    public record ProductDetailResponse(@JsonUnwrapped ProductResponse summary, String detail, Map<String, String> specs,
                                        List<String> images, List<ProductResponse> related) {
    }

    public record CategoryNode(String code, String label, long count, List<CategoryNode> children) {
    }

    public record CategoryTree(long total, long deals, List<CategoryNode> categories) {
    }
}
