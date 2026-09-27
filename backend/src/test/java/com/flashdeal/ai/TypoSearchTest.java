package com.flashdeal.ai;

import com.flashdeal.product.Product;
import com.flashdeal.product.ProductDtos.ProductResponse;
import com.flashdeal.product.ProductRepository;
import com.flashdeal.product.SubCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** API 키가 없는 테스트 환경 = 규칙 기반 경로에서 오타 교정이 동작하는지 */
@SpringBootTest(properties = "flashdeal.ai.api-key=")
@ActiveProfiles("test")
class TypoSearchTest {

    @Autowired AiSearchService service;
    @Autowired ProductRepository productRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("delete from order_item");
        jdbc.execute("delete from orders");
        jdbc.execute("delete from product");
        productRepository.saveAll(List.of(
                Product.builder().name("무선 버티컬 마우스").subCategory(SubCategory.MOUSE_WIRELESS).price(19_900).stock(10).build(),
                Product.builder().name("초경량 게이밍 마우스").subCategory(SubCategory.MOUSE_GAMING).price(45_000).stock(10).build(),
                Product.builder().name("저소음 무선 키보드").subCategory(SubCategory.KEYBOARD_WIRELESS).price(29_000).stock(10).build()));
    }

    @Test
    @DisplayName("'마으수' → 결과 0건이라 '마우스'로 교정해서 찾고, 원래 검색어를 함께 알려준다")
    void correctsTypo() {
        AiSearchService.AiSearchResponse r = service.search("마으수");

        assertThat(r.originalQuery()).isEqualTo("마으수");
        assertThat(r.query()).isEqualTo("마우스");
        assertThat(r.products()).extracting(ProductResponse::name).allMatch(n -> n.contains("마우스")).hasSize(2);
    }

    @Test
    @DisplayName("여러 단어 중 틀린 단어만 교정: '게이밍 마으스' → '게이밍 마우스'")
    void correctsOnlyWrongToken() {
        AiSearchService.AiSearchResponse r = service.search("게이밍 마으스");

        assertThat(r.query()).isEqualTo("게이밍 마우스");
        assertThat(r.products()).extracting(ProductResponse::name).containsExactly("초경량 게이밍 마우스");
    }

    @Test
    @DisplayName("정상 검색에는 개입하지 않는다")
    void noCorrectionWhenResultsExist() {
        AiSearchService.AiSearchResponse r = service.search("무선 키보드");

        assertThat(r.originalQuery()).isNull();
        assertThat(r.products()).extracting(ProductResponse::name).containsExactly("저소음 무선 키보드");
    }

    @Test
    @DisplayName("사전에 가까운 단어가 없으면 교정하지 않고, 다른 조건이 없으면 전체 상품으로 완화하지도 않는다")
    void noWildGuess() {
        AiSearchService.AiSearchResponse r = service.search("사과");

        assertThat(r.originalQuery()).isNull();
        assertThat(r.products()).isEmpty();
    }

    @Test
    @DisplayName("사용자가 '원래 검색어로 검색'을 고르면 교정하지 않는다")
    void optOut() {
        AiSearchService.AiSearchResponse r = service.search("마으수", false);

        assertThat(r.originalQuery()).isNull();
        assertThat(r.products()).isEmpty();
    }

    @Test
    @DisplayName("자모 편집거리: '마으수'와 '마우스'는 모음 2개 차이")
    void jamoDistance() {
        assertThat(TypoCorrector.distance(TypoCorrector.jamo("마으수"), TypoCorrector.jamo("마우스"))).isEqualTo(2);
        assertThat(TypoCorrector.distance(TypoCorrector.jamo("키보두"), TypoCorrector.jamo("키보드"))).isEqualTo(1);
        assertThat(TypoCorrector.distance(TypoCorrector.jamo("모니터"), TypoCorrector.jamo("모니터"))).isZero();
    }
}
