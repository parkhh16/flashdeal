package com.flashdeal.ai;

import com.flashdeal.product.ProductCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedQueryParserTest {

    private final RuleBasedQueryParser parser = new RuleBasedQueryParser();

    @Test
    @DisplayName("가격 상한 + 카테고리 + 키워드")
    void maxPrice() {
        SearchFilter f = parser.parse("5만원 이하 무선 마우스 추천해줘");
        assertThat(f.maxPrice()).isEqualTo(50_000);
        assertThat(f.minPrice()).isNull();
        assertThat(f.category()).isEqualTo(ProductCategory.MOUSE);
        assertThat(f.keyword()).isEqualTo("무선");
    }

    @Test
    @DisplayName("가격 범위")
    void range() {
        SearchFilter f = parser.parse("10~20만원 모니터");
        assertThat(f.minPrice()).isEqualTo(100_000);
        assertThat(f.maxPrice()).isEqualTo(200_000);
        assertThat(f.category()).isEqualTo(ProductCategory.MONITOR);
    }

    @Test
    @DisplayName("가격 하한 + 대분류보다 좁은 단어(헤드셋)는 키워드로도 남겨서 스피커·이어폰이 섞이지 않게")
    void minPrice() {
        SearchFilter f = parser.parse("3만원 이상 헤드셋");
        assertThat(f.minPrice()).isEqualTo(30_000);
        assertThat(f.category()).isEqualTo(ProductCategory.AUDIO);
        assertThat(f.keyword()).isEqualTo("헤드셋");
    }

    @Test
    @DisplayName("부분 입력: '마우', '키보'처럼 타이핑 중인 단어도 카테고리로 인식한다")
    void partialInput() {
        assertThat(parser.parse("마우").category()).isEqualTo(ProductCategory.MOUSE);
        assertThat(parser.parse("무선 키보").category()).isEqualTo(ProductCategory.KEYBOARD);
        assertThat(parser.parse("무선 키보").keyword()).isEqualTo("무선");
        assertThat(parser.parse("마").category()).as("한 글자는 오탐이 많아 무시").isNull();
    }

    @Test
    @DisplayName("LLM이 이상한 값을 줘도 sanitize에서 걸러진다")
    void sanitize() {
        SearchFilter f = new SearchFilter("  ", null, 90_000L, 10_000L).sanitized();
        assertThat(f.minPrice()).isEqualTo(10_000);
        assertThat(f.maxPrice()).isEqualTo(90_000);
        assertThat(f.keyword()).isNull();

        SearchFilter negative = new SearchFilter(null, null, -1L, 999_999_999_999L).sanitized();
        assertThat(negative.minPrice()).isNull();
        assertThat(negative.maxPrice()).isNull();
    }
}
