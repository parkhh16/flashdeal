package com.needsshop.product;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchTextTest {

    private static final String KEYBOARD = SearchText.build("저소음 무선 키보드", "사무실용 저소음 적축",
            "도서관처럼 조용한 사무실에서도 부담 없는 키보드입니다.", specs("연결", "2.4GHz 무선", "배열", "풀배열 104키"));

    @Test
    @DisplayName("검색용 문자열: 소문자, 구두점은 공백으로, 문장 끝 마침표는 빼고 '2.4GHz'의 점은 남김, 스펙은 항목과 값 모두, 앞뒤 공백")
    void build() {
        assertThat(KEYBOARD).isEqualTo(" 저소음 무선 키보드 사무실용 저소음 적축 도서관처럼 조용한 사무실에서도 부담 없는 키보드입니다"
                + " 연결 2.4ghz 무선 배열 풀배열 104키 ");
        assertThat(SearchText.build("USB-C 7in1 (멀티) 허브", null, null, null)).isEqualTo(" usb-c 7in1 멀티 허브 ");
    }

    @Test
    @DisplayName("띄어쓰기를 무시하고 포함: '데스크매트' ↔ '데스크 매트', '패드' ↔ '장패드'")
    void spacingAndInsideWord() {
        String pad = SearchText.build("대형 장패드", "900x400 데스크 매트", null, null);
        assertThat(SearchText.matches(pad, "데스크매트")).isTrue();
        assertThat(SearchText.matches(pad, "데스크")).isTrue();
        assertThat(SearchText.matches(pad, "패드")).isTrue();
    }

    @Test
    @DisplayName("한글 세 글자 이상은 앞 두 글자로 시작하는 단어: '사무실에서' ↔ '사무실용', '조용히' ↔ '조용한'")
    void koreanEndings() {
        assertThat(SearchText.matches(KEYBOARD, "사무실에서")).isTrue();
        assertThat(SearchText.matches(KEYBOARD, "조용히")).isTrue();
        assertThat(SearchText.matches(KEYBOARD, "가벼운")).isFalse();
        assertThat(SearchText.matches(" 초경량 가벼움 ", "가벼운")).isTrue();
    }

    @Test
    @DisplayName("한글 한 글자는 같은 단어만: '눈'은 '눈 편한'에는 맞고 '눈높이'에는 안 맞는다")
    void singleSyllable() {
        assertThat(SearchText.matches(" 눈 편한 플리커프리 ", "눈")).isTrue();
        assertThat(SearchText.matches(" 화면을 눈높이로 ", "눈")).isFalse();
        assertThat(SearchText.isWeak("눈")).isTrue();
        assertThat(SearchText.isWeak("a")).isFalse();
    }

    @Test
    @DisplayName("표기 차이 사전: 'wireless' ↔ '무선', '블투' ↔ '블루투스'. 뜻이 다른 말('조용한' ↔ '저소음')은 사전에 없다")
    void spellings() {
        assertThat(SearchText.matchesAny(KEYBOARD, "wireless")).isTrue();
        assertThat(SearchText.matchesAny(" 블루투스 5.3 ", "블투")).isTrue();
        assertThat(SearchText.variants("조용한")).containsExactly("조용한");
    }

    @Test
    @DisplayName("LIKE 패턴: 사용자 입력의 %, _ 는 글자로 취급한다")
    void escape() {
        assertThat(SearchText.pattern("100%").anywhere()).isEqualTo("%100!%%");
        assertThat(SearchText.pattern("눈").anywhere()).isNull();
        assertThat(SearchText.pattern("눈").wordStart()).isEqualTo("% 눈 %");
        assertThat(SearchText.pattern("사무실에서").wordStart()).isEqualTo("% 사무%");
        assertThat(SearchText.pattern("usb-c").wordStart()).isNull();
    }

    private static Map<String, String> specs(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
