package com.flashdeal.product;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.util.List;
import java.util.Map;

public final class ProductDtos {

    private ProductDtos() {
    }

    public enum Sort {POPULAR, PRICE_ASC, PRICE_DESC}

    public record SearchCondition(ProductCategory category, SubCategory sub, Long minPrice,
                                  Long maxPrice, boolean excludeSoldOut, Sort sort, String keyword) {

        public SearchCondition(ProductCategory category, SubCategory sub, Long minPrice, Long maxPrice,
                               boolean excludeSoldOut, Sort sort) {
            this(category, sub, minPrice, maxPrice, excludeSoldOut, sort, null);
        }
    }

    /** 목록용. 상세 설명/스펙처럼 무거운 필드는 빼서 응답 크기를 줄인다 */
    public record ProductResponse(Long id, String name, ProductCategory category, String categoryLabel,
                                  SubCategory subCategory, String subCategoryLabel, long price, Long originalPrice,
                                  int discountRate, int stock, boolean soldOut, int soldCount, String description,
                                  String thumbnail, Integer perUserLimit) {

        public static ProductResponse from(Product p) {
            return new ProductResponse(p.getId(), p.getName(), p.getCategory(), p.getCategory().getLabel(),
                    p.getSubCategory(), p.getSubCategory().getLabel(), p.getPrice(), p.getOriginalPrice(),
                    p.discountRate(), p.getStock(), p.isSoldOut(), p.getSoldCount(), p.getDescription(), p.thumbnail(),
                    p.getPerUserLimit());
        }
    }

    /** 목록 필드를 평탄화(@JsonUnwrapped)해서 기존 GET /api/products/{id} 응답 형태(stock 등 최상위 필드)를 유지한다 */
    public record ProductDetailResponse(@JsonUnwrapped ProductResponse summary, String detail, Map<String, String> specs,
                                        List<String> images, List<ProductResponse> related) {
    }

    public record CategoryNode(String code, String label, long count, List<CategoryNode> children) {
    }

    public record CategoryTree(long total, List<CategoryNode> categories) {
    }
}
