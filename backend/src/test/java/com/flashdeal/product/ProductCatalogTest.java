package com.flashdeal.product;

import com.flashdeal.product.ProductDtos.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
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
        LocalDateTime now = LocalDateTime.now();
        repository.saveAll(List.of(
                Product.builder().name("무선A").subCategory(SubCategory.MOUSE_WIRELESS).price(20_000).stock(10).build(),
                Product.builder().name("게이밍B").subCategory(SubCategory.MOUSE_GAMING).price(50_000).stock(0).build(),
                Product.builder().name("특가C").subCategory(SubCategory.MOUSE_WIRELESS).price(10_000).originalPrice(20_000L)
                        .stock(5).dealStartAt(now.minusHours(1)).dealEndAt(now.plusHours(1)).dealQuantity(10).build(),
                Product.builder().name("특가D").subCategory(SubCategory.KEYBOARD_GAMING).price(30_000).originalPrice(40_000L)
                        .stock(5).dealStartAt(now.plusHours(1)).dealEndAt(now.plusHours(5)).dealQuantity(10).build(),
                Product.builder().name("종료E").subCategory(SubCategory.KEYBOARD_GAMING).price(1_000).stock(5)
                        .dealStartAt(now.minusHours(5)).dealEndAt(now.minusHours(1)).build()));
    }

    @Test
    @DisplayName("카테고리 트리: 소분류별 개수, 대분류 합계, 상품 0개인 소분류도 포함")
    void categoryTree() {
        CategoryTree tree = service.categories();

        assertThat(tree.total()).isEqualTo(5);
        assertThat(tree.deals()).as("종료된 특가는 제외").isEqualTo(2);
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
                ProductCategory.MOUSE, null, null, null, 30_000L, true, Sort.PRICE_ASC));
        assertThat(result).extracting(ProductResponse::name).containsExactly("특가C", "무선A");

        List<ProductResponse> gaming = service.search(new SearchCondition(
                null, SubCategory.MOUSE_GAMING, null, null, null, false, Sort.POPULAR));
        assertThat(gaming).extracting(ProductResponse::name).containsExactly("게이밍B");
    }

    @Test
    @DisplayName("오늘의 특가: 진행 중 + 오픈 예정만, 마감임박 순")
    void deals() {
        List<ProductResponse> result = service.search(new SearchCondition(
                null, null, true, null, null, false, Sort.DEADLINE));
        assertThat(result).extracting(ProductResponse::name).containsExactly("특가C", "특가D");
        assertThat(result.get(0).dealStatus()).isEqualTo(DealStatus.ONGOING);
        assertThat(result.get(0).discountRate()).isEqualTo(50);
        assertThat(result.get(1).dealStatus()).isEqualTo(DealStatus.UPCOMING);
    }

    @Test
    @DisplayName("마감임박 정렬: 진행 중 특가가 먼저, 일반/종료 상품은 뒤로")
    void deadlineSortPutsActiveDealsFirst() {
        List<ProductResponse> result = service.search(new SearchCondition(
                null, null, null, null, null, false, Sort.DEADLINE));
        assertThat(result.subList(0, 2)).extracting(ProductResponse::name).containsExactly("특가C", "특가D");
    }

    @Test
    @DisplayName("키워드 필터: 부분 일치, %는 와일드카드가 아니라 글자로 취급")
    void keyword() {
        assertThat(service.search(new SearchCondition(null, null, null, null, null, false, Sort.PRICE_ASC, "특가")))
                .extracting(ProductResponse::name).containsExactly("특가C", "특가D");
        assertThat(service.search(new SearchCondition(null, null, null, null, null, false, Sort.PRICE_ASC, "%"))).isEmpty();
    }

    @Test
    @DisplayName("가격 정렬")
    void priceSort() {
        assertThat(service.search(new SearchCondition(null, null, null, null, null, false, Sort.PRICE_DESC)))
                .extracting(ProductResponse::price).isSortedAccordingTo((a, b) -> Long.compare(b, a));
    }
}
