package com.needsshop.ai;

import com.needsshop.product.SearchText;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI가 만든 태그를 코드가 검사하는 규칙 ({@link ProductTagger#check}). AI가 판단하고, 사실과 어긋나는 것은 코드가 뺀다.
 */
class ProductTaggerTest {

    private static final String WIRED_KEYBOARD = SearchText.build("무접점 기계식 키보드", "정전용량 무접점",
            "부드럽고 조용한 타건감", Map.of("연결", "USB-C 유선"));
    private static final String SPEAKER = SearchText.build("블루투스 포터블 스피커", "캠핑용 스피커",
            "야외에서 쓰기 좋은 스피커", Map.of("방수", "IP67", "연결", "블루투스 5.3"));

    @Test
    @DisplayName("단어로 나누고, 한 글자와 12글자 넘는 말은 뺀다")
    void words() {
        assertThat(ProductTagger.check(WIRED_KEYBOARD, List.of("코딩 작업", "밤", "아주아주아주아주긴설명같은태그")))
                .containsExactly("코딩", "작업");
    }

    @Test
    @DisplayName("가격·광고 표현은 뺀다 (가격은 사실이라 가격 필터가 다룬다)")
    void priceAndAdWords() {
        assertThat(ProductTagger.check(WIRED_KEYBOARD, List.of("가성비", "저렴한", "추천", "고성능", "타자감")))
                .containsExactly("타자감");
    }

    @Test
    @DisplayName("다른 상품 종류 이름은 뺀다 (스피커에 '헤드셋대용'이 붙으면 헤드셋 검색에 나온다)")
    void otherTypeNames() {
        assertThat(ProductTagger.check(SPEAKER, List.of("헤드셋대용", "마우스", "야영")))
                .containsExactly("야영");
    }

    @Test
    @DisplayName("상품 정보에 근거가 없는 기능은 뺀다 (유선 키보드에 '무선', 방수 표시 없는 상품에 '방수')")
    void featureNeedsEvidence() {
        assertThat(ProductTagger.check(WIRED_KEYBOARD, List.of("무선", "wireless", "방수", "개발자")))
                .containsExactly("개발자");
        assertThat(ProductTagger.check(SPEAKER, List.of("생활방수", "샤워")))
                .as("IP67이 근거").containsExactly("생활방수", "샤워");
        assertThat(ProductTagger.check(SPEAKER, List.of("무소음", "조용한")))
                .as("소음 얘기가 없는 상품").isEmpty();
        assertThat(ProductTagger.check(WIRED_KEYBOARD, List.of("무소음")))
                .as("'조용한 타건감'이 근거").containsExactly("무소음");
    }

    @Test
    @DisplayName("이미 상품 정보에 있는 말은 뺀다 (태그 없이도 찾힌다)")
    void alreadyInProduct() {
        assertThat(ProductTagger.check(WIRED_KEYBOARD, List.of("조용한", "무접점", "사무실")))
                .containsExactly("사무실");
    }

    @Test
    @DisplayName("뺀 말은 이유와 함께 남긴다 (AI가 무엇을 잘못 제안하는지 로그로 본다)")
    void droppedWithReason() {
        var checked = ProductTagger.inspect(SPEAKER, List.of("야영", "가성비", "헤드셋대용", "무소음", "캠핑용"));

        assertThat(checked.kept()).containsExactly("야영");
        assertThat(checked.dropped()).containsExactly(
                java.util.Map.entry("가성비", "가격·광고"), java.util.Map.entry("헤드셋대용", "다른 종류"),
                java.util.Map.entry("무소음", "근거 없음"), java.util.Map.entry("캠핑용", "이미 있음"));
    }

    @Test
    @DisplayName("최대 15개, 중복 없이")
    void limit() {
        List<String> many = IntStream.range(0, 30).mapToObj(i -> "태그" + i).toList();
        assertThat(ProductTagger.check(WIRED_KEYBOARD, many)).hasSize(ProductTagger.MAX_TAGS);
        assertThat(ProductTagger.check(WIRED_KEYBOARD, List.of("사무실", "사무실"))).containsExactly("사무실");
    }
}
