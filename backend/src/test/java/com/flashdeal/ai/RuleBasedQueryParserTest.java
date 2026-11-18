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
    @DisplayName("군더더기 말은 단어 전체가 일치할 때만 뺀다: '조용히'의 '용', '비싼'의 '싼'은 남긴다")
    void fillerWordsOnlyAsWholeWords() {
        assertThat(parser.parse("조용히").keyword()).isEqualTo("조용히");
        assertThat(parser.parse("조용한 키보드").keyword()).isEqualTo("조용한");
        assertThat(parser.parse("비싼 키보드").keyword()).isEqualTo("비싼");
        assertThat(parser.parse("맥 전용 키보드 좀 찾아줘").keyword()).isEqualTo("맥 전용");
        assertThat(parser.parse("싼거 추천").keyword()).isNull();
    }

    @Test
    @DisplayName("'게임용', '사무용'의 '용'은 떼되, 떼고 한 글자만 남는 '사용'·'전용'은 그대로 둔다")
    void usageSuffix() {
        assertThat(parser.parse("게임용 마우스").keyword()).isEqualTo("게임");
        assertThat(parser.parse("사무용 키보드").keyword()).isEqualTo("사무");
        assertThat(parser.parse("전용 키보드").keyword()).isEqualTo("전용");
    }

    @Test
    @DisplayName("한글 금액: '오만원', '삼만오천원', '십오만원'")
    void koreanAmount() {
        assertThat(parser.parse("오만원 이하 키보드").maxPrice()).isEqualTo(50_000);
        assertThat(parser.parse("삼만오천원 이상 마우스").minPrice()).isEqualTo(35_000);
        assertThat(parser.parse("십오만원 이하 모니터").maxPrice()).isEqualTo(150_000);
        assertThat(parser.parse("만원 이하").maxPrice()).isEqualTo(10_000);
        assertThat(parser.parse("5만원 이하").maxPrice()).as("숫자 뒤의 '만'은 한글 금액이 아님").isEqualTo(50_000);
        assertThat(parser.parse("오디오 추천").keyword()).as("원이 안 붙은 '오'는 그대로").isEqualTo("오디오");
    }

    @Test
    @DisplayName("쉼표가 들어간 금액: '35,000원', '1,200,000원'")
    void thousandsComma() {
        assertThat(parser.parse("35,000원 이하 마우스").maxPrice()).isEqualTo(35_000);
        assertThat(parser.parse("1,200,000원 이하 모니터").maxPrice()).isEqualTo(1_200_000);
    }

    @Test
    @DisplayName("말로 쓴 가격 범위: '4만원에서 6만원 사이', '3만원부터 5만원까지', '35000~50000원'")
    void spokenRange() {
        SearchFilter between = parser.parse("4만원에서 6만원 사이 키보드");
        assertThat(between.minPrice()).isEqualTo(40_000);
        assertThat(between.maxPrice()).isEqualTo(60_000);
        assertThat(between.category()).isEqualTo(ProductCategory.KEYBOARD);
        assertThat(between.keyword()).isNull();

        SearchFilter fromTo = parser.parse("3만원부터 5만원까지");
        assertThat(fromTo.minPrice()).isEqualTo(30_000);
        assertThat(fromTo.maxPrice()).isEqualTo(50_000);

        SearchFilter won = parser.parse("35000~50000원 마우스");
        assertThat(won.minPrice()).isEqualTo(35_000);
        assertThat(won.maxPrice()).isEqualTo(50_000);
    }

    @Test
    @DisplayName("단위 없는 'G502-2' 같은 모델명은 가격 범위로 보지 않는다")
    void modelNameIsNotPriceRange() {
        SearchFilter f = parser.parse("G502-2 마우스");
        assertThat(f.minPrice()).isNull();
        assertThat(f.maxPrice()).isNull();
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

    @Test
    @DisplayName("상품 종류 단어가 여럿이면 맨 뒤가 중심: '마우스 패드'는 패드(액세서리), '키보드 꾸미기용 키캡'은 키캡")
    void headWordDecidesCategory() {
        SearchFilter pad = parser.parse("마우스 패드");
        assertThat(pad.category()).isEqualTo(ProductCategory.ACCESSORY);
        assertThat(pad.keyword()).isEqualTo("마우스 패드");

        assertThat(parser.parse("키보드 꾸미기용 키캡").category()).isEqualTo(ProductCategory.ACCESSORY);
        assertThat(parser.parse("맥북에 모니터랑 USB 연결할 허브").category()).isEqualTo(ProductCategory.ACCESSORY);
        assertThat(parser.parse("헤드셋 거치대").category()).isEqualTo(ProductCategory.ACCESSORY);
    }

    @Test
    @DisplayName("붙여 쓴 말을 띄운다: '마우스패드' = '마우스 패드', '무선마우스' = '무선 마우스'. 남는 쪽이 한 글자면 그대로('장패드')")
    void splitCompounds() {
        assertThat(parser.parse("마우스패드").keyword()).isEqualTo("마우스 패드");
        assertThat(parser.parse("마우스패드").category()).isEqualTo(ProductCategory.ACCESSORY);

        SearchFilter mouse = parser.parse("무선마우스");
        assertThat(mouse.category()).isEqualTo(ProductCategory.MOUSE);
        assertThat(mouse.keyword()).isEqualTo("무선");

        assertThat(parser.parse("장패드").keyword()).isEqualTo("장패드");
        assertThat(RuleBasedQueryParser.splitCompounds("모니터랑 허브")).isEqualTo("모니터랑 허브");
    }

    @Test
    @DisplayName("가격대: '5만원대' = 5만~59,999원, '만원대' = 1만~19,999원, '10만원대' = 10만~199,999원")
    void priceBand() {
        SearchFilter five = parser.parse("5만원대 마우스");
        assertThat(five.minPrice()).isEqualTo(50_000);
        assertThat(five.maxPrice()).isEqualTo(59_999);
        assertThat(five.keyword()).isNull();

        SearchFilter ten = parser.parse("만원대 장패드");
        assertThat(ten.minPrice()).isEqualTo(10_000);
        assertThat(ten.maxPrice()).isEqualTo(19_999);

        assertThat(parser.parse("10만원대 헤드셋").maxPrice()).isEqualTo(199_999);
        assertThat(RuleBasedQueryParser.bandWidth(150_000)).isEqualTo(10_000);
        assertThat(parser.parse("10대 키보드").minPrice()).as("단위가 없으면 가격대가 아니다").isNull();
    }

    @Test
    @DisplayName("가격 조건 두 개: '20만원 이상 30만원 이하', 상한 표현 '안쪽'")
    void twoPriceBounds() {
        SearchFilter f = parser.parse("20만원 이상 30만원 이하");
        assertThat(f.minPrice()).isEqualTo(200_000);
        assertThat(f.maxPrice()).isEqualTo(300_000);
        assertThat(f.keyword()).isNull();

        assertThat(parser.parse("십만원 안쪽 헤드셋").maxPrice()).isEqualTo(100_000);
        assertThat(parser.parse("144Hz 이상 모니터").minPrice()).as("단위가 Hz면 가격이 아니다").isNull();
    }

    @Test
    @DisplayName("제외 조건: '게이밍 말고', '유선 아닌'은 키워드가 아니라 뺄 단어")
    void excludes() {
        SearchFilter f = parser.parse("게이밍 말고 사무용 모니터");
        assertThat(f.excludes()).containsExactly("게이밍");
        assertThat(f.keyword()).isEqualTo("사무");
        assertThat(f.category()).isEqualTo(ProductCategory.MONITOR);

        SearchFilter earphone = parser.parse("헤드셋 말고 이어폰");
        assertThat(earphone.excludes()).containsExactly("헤드셋");
        assertThat(earphone.keyword()).isEqualTo("이어폰");
    }

    @Test
    @DisplayName("영어 상품명과 가격: 'wireless mouse under 30000', 'gaming headset'")
    void english() {
        SearchFilter mouse = parser.parse("wireless mouse under 30000");
        assertThat(mouse.category()).isEqualTo(ProductCategory.MOUSE);
        assertThat(mouse.maxPrice()).isEqualTo(30_000);
        assertThat(mouse.keyword()).isEqualTo("wireless");

        SearchFilter headset = parser.parse("gaming headset");
        assertThat(headset.category()).isEqualTo(ProductCategory.AUDIO);
        assertThat(headset.keyword()).as("좁은 종류는 한글로 남긴다").isEqualTo("gaming 헤드셋");
    }

    @Test
    @DisplayName("상품 종류의 끝 글자 오타: '키보트' → 키보드, '헤드폰' → 헤드셋. 다른 상품 종류 단어가 있으면 그쪽이 우선")
    void typeWordTypo() {
        SearchFilter f = parser.parse("키보트 3만원 이하");
        assertThat(f.category()).isEqualTo(ProductCategory.KEYBOARD);
        assertThat(f.keyword()).isNull();

        SearchFilter headphone = parser.parse("헤드폰");
        assertThat(headphone.category()).isEqualTo(ProductCategory.AUDIO);
        assertThat(headphone.keyword()).isEqualTo("헤드셋");

        assertThat(RuleBasedQueryParser.lastSyllableTypo("마우쓰", "마우스")).isTrue();
        assertThat(RuleBasedQueryParser.lastSyllableTypo("마우", "마우스")).isFalse();
        assertThat(RuleBasedQueryParser.lastSyllableTypo("마으수", "마우스")).as("가운데 오타는 오타 교정기 몫").isFalse();
    }

    @Test
    @DisplayName("한 글자 기능어(때, 쓸, 안)는 키워드에서 뺀다: 상품 설명의 '편하게 쓸 수 있습니다'와 맞지 않게")
    void singleSyllableFunctionWords() {
        assertThat(parser.parse("게임할 때 쓸 가벼운 마우스").keyword()).isEqualTo("게임할 가벼운");
        assertThat(parser.parse("눈 안 아픈 모니터").keyword()).isEqualTo("눈 아픈");
    }

    @Test
    @DisplayName("'아무거나 추천해줘'는 조건이 없는 검색")
    void anything() {
        SearchFilter f = parser.parse("아무거나 추천해줘");
        assertThat(f.keyword()).isNull();
        assertThat(f.category()).isNull();
    }
}
