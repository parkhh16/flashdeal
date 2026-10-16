package com.flashdeal.product;

import com.flashdeal.product.ProductDtos.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ProductCatalogTest {

    @Autowired ProductQueryService service;
    @Autowired ProductRepository repository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("delete from order_item");
        jdbc.execute("delete from orders");
        jdbc.execute("delete from product");
        repository.saveAll(List.of(
                Product.builder().name("무선A").subCategory(SubCategory.MOUSE_WIRELESS).price(20_000).stock(10).build(),
                Product.builder().name("게이밍B").subCategory(SubCategory.MOUSE_GAMING).price(50_000).stock(0).build(),
                Product.builder().name("할인C").subCategory(SubCategory.MOUSE_WIRELESS).price(10_000).originalPrice(20_000L)
                        .stock(5).build(),
                Product.builder().name("키보드D").subCategory(SubCategory.KEYBOARD_GAMING).price(30_000).originalPrice(40_000L)
                        .stock(5).build(),
                Product.builder().name("키보드E").subCategory(SubCategory.KEYBOARD_GAMING).price(1_000).stock(5).build()));
    }

    @Test
    @DisplayName("카테고리 트리: 소분류별 개수, 대분류 합계, 상품 0개인 소분류도 포함")
    void categoryTree() {
        CategoryTree tree = service.categories();

        assertThat(tree.total()).isEqualTo(5);
        CategoryNode mouse = tree.categories().stream().filter(c -> c.code().equals("MOUSE")).findFirst().orElseThrow();
        assertThat(mouse.label()).isEqualTo("마우스");
        assertThat(mouse.count()).isEqualTo(3);
        assertThat(mouse.children()).extracting(CategoryNode::label).containsExactly("무선", "게이밍");
        CategoryNode monitor = tree.categories().stream().filter(c -> c.code().equals("MONITOR")).findFirst().orElseThrow();
        assertThat(monitor.count()).isZero();
        assertThat(monitor.children()).isNotEmpty();
    }

    @Test
    @DisplayName("필터: 소분류 + 품절 제외 + 가격 상한")
    void filters() {
        List<ProductResponse> result = service.search(new SearchCondition(
                ProductCategory.MOUSE, null, null, 30_000L, true, Sort.PRICE_ASC));
        assertThat(result).extracting(ProductResponse::name).containsExactly("할인C", "무선A");

        List<ProductResponse> gaming = service.search(new SearchCondition(
                null, SubCategory.MOUSE_GAMING, null, null, false, Sort.POPULAR));
        assertThat(gaming).extracting(ProductResponse::name).containsExactly("게이밍B");
    }

    @Test
    @DisplayName("품절 여부와 할인율은 응답에서 계산해서 내려준다 (정가가 없으면 할인율 0)")
    void soldOutAndDiscount() {
        List<ProductResponse> all = service.search(new SearchCondition(null, null, null, null, false, Sort.PRICE_ASC));

        ProductResponse soldOut = all.stream().filter(p -> p.name().equals("게이밍B")).findFirst().orElseThrow();
        ProductResponse discounted = all.stream().filter(p -> p.name().equals("할인C")).findFirst().orElseThrow();
        ProductResponse regular = all.stream().filter(p -> p.name().equals("무선A")).findFirst().orElseThrow();
        assertThat(soldOut.soldOut()).isTrue();
        assertThat(discounted.soldOut()).isFalse();
        assertThat(discounted.discountRate()).isEqualTo(50);
        assertThat(regular.discountRate()).isZero();
    }

    @Test
    @DisplayName("인기순: 판매량(sold_count)이 많은 순서, 같으면 등록 순")
    void popularSort() {
        jdbc.update("update product set sold_count = 7 where name = '키보드E'");
        jdbc.update("update product set sold_count = 3 where name = '무선A'");

        List<ProductResponse> result = service.search(new SearchCondition(null, null, null, null, false, Sort.POPULAR));

        assertThat(result.subList(0, 2)).extracting(ProductResponse::name).containsExactly("키보드E", "무선A");
    }

    @Test
    @DisplayName("키워드 필터: 부분 일치, %는 와일드카드가 아니라 글자로 취급")
    void keyword() {
        assertThat(service.search(new SearchCondition(null, null, null, null, false, Sort.PRICE_ASC, "키보드")))
                .extracting(ProductResponse::name).containsExactly("키보드E", "키보드D");
        assertThat(service.search(new SearchCondition(null, null, null, null, false, Sort.PRICE_ASC, "%"))).isEmpty();
    }

    @Test
    @DisplayName("가격 정렬")
    void priceSort() {
        assertThat(service.search(new SearchCondition(null, null, null, null, false, Sort.PRICE_DESC)))
                .extracting(ProductResponse::price).isSortedAccordingTo((a, b) -> Long.compare(b, a));
    }
}
