package com.flashdeal.product;

import com.flashdeal.activity.ActivityContext;
import com.flashdeal.ai.AiSearchService;
import com.flashdeal.ai.AiSearchService.AiSearchResponse;
import com.flashdeal.product.ProductDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Product")
@Validated
@RestController
@RequiredArgsConstructor
public class ProductController {

    private final ProductQueryService productQueryService;
    private final AiSearchService aiSearchService;

    @Operation(summary = "상품 목록", description = "category/sub(소분류)/deal(진행·예정 특가)/가격대/품절 제외 필터 + 정렬")
    @GetMapping("/api/products")
    public List<ProductResponse> list(@RequestParam(required = false) ProductCategory category,
                                      @RequestParam(required = false) SubCategory sub,
                                      @RequestParam(required = false) Boolean deal,
                                      @RequestParam(required = false) Long minPrice,
                                      @RequestParam(required = false) Long maxPrice,
                                      @RequestParam(defaultValue = "false") boolean excludeSoldOut,
                                      @RequestParam(defaultValue = "POPULAR") Sort sort,
                                      @RequestParam(required = false) @Size(max = 30) String keyword) {
        return productQueryService.search(new SearchCondition(category, sub, deal, minPrice, maxPrice, excludeSoldOut, sort, keyword));
    }

    @Operation(summary = "카테고리 트리 (사이드바)", description = "대분류 > 소분류와 상품 개수")
    @GetMapping("/api/categories")
    public CategoryTree categories() {
        return productQueryService.categories();
    }

    @Operation(summary = "상품 상세", description = "상세 설명, 스펙, 이미지 목록, 같은 소분류 관련 상품")
    @GetMapping("/api/products/{id}")
    public ProductDetailResponse get(@PathVariable Long id) {
        return productQueryService.detail(id);
    }

    @Operation(summary = "AI 자연어 검색", description = "예: \"5만원 이하 무선 마우스\". LLM은 필터만 만들고 상품은 DB에서 조회한다. LLM 장애 시 규칙 기반으로 폴백")
    @GetMapping("/api/products/search")
    public AiSearchResponse aiSearch(@RequestParam @NotBlank @Size(max = 100) String q,
                                     @RequestParam(defaultValue = "true") boolean correct) {
        AiSearchResponse result = aiSearchService.search(q, correct);
        ActivityContext.put("q", result.query());
        ActivityContext.put("results", result.products().size());
        ActivityContext.put("parsedBy", result.parsedBy());
        ActivityContext.put("correctedFrom", result.originalQuery());
        return result;
    }
}
