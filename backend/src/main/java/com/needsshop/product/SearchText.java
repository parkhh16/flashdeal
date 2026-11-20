package com.needsshop.product;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 상품 검색용 문자열과 단어 매칭 규칙. 저장하는 쪽(상품)과 찾는 쪽(검색어)이 같은 규칙을 써야 해서 한곳에 둔다.
 *
 * <p>저장: 이름, 짧은 설명, 상세 설명, 스펙(항목과 값)을 소문자로 바꾸고 구두점을 공백으로 바꿔 단어 사이를 공백 하나로,
 * 앞뒤에도 공백을 붙인다. 예: {@code " 저소음 무선 키보드 사무실용 저소음 적축 도서관처럼 조용한 ... 스위치 저소음 적축 "}
 *
 * <p>검색어 단어 하나는 다음 중 하나면 맞은 것으로 본다.
 * <ol>
 *   <li>띄어쓰기를 무시하고 들어 있음: "데스크매트" ↔ "데스크 매트", "패드" ↔ "장패드", "usb-c" ↔ "USB-C"</li>
 *   <li>한글 세 글자 이상이면, 앞 두 글자로 시작하는 단어가 있음: "가벼운" ↔ "가벼움", "사무실에서" ↔ "사무실용".
 *       한국어는 뒤에 붙는 조사·어미가 바뀌므로 앞부분으로 맞춘다</li>
 *   <li>한글 한 글자는 같은 단어가 있을 때만: "눈" ↔ "눈 편한". 포함으로 보면 "눈높이"처럼 엉뚱한 상품이 많이 걸린다</li>
 * </ol>
 */
public final class SearchText {

    /** product.search_text 컬럼 길이 */
    public static final int MAX_LENGTH = 4000;

    /** 단어 구분자. 마침표는 문장 끝("키보드입니다.")에서만 구분자이고 "2.4GHz"처럼 단어 안에서는 남긴다 */
    private static final Pattern SEPARATORS = Pattern.compile("[\\s,()/·|:;\\[\\]{}\"'+~!?]+|\\.(?=\\s|$)");
    private static final Pattern HANGUL = Pattern.compile("[가-힣]");

    /**
     * 표기 차이 사전: 뜻은 같고 쓰는 방식(영어 표기, 줄임말, 한글 표기)만 다른 말. 한 묶음 안의 말은 서로 같은 단어로 본다.
     * 뜻이 다른 표현("몰컴" → 저소음, "가벼운" → 초경량)은 여기에 넣지 않는다. 그건 상품 태그와 검색어 사전(AI)의 몫이다.
     */
    private static final List<List<String>> SPELLINGS = List.of(
            List.of("무선", "wireless"),
            List.of("블루투스", "bluetooth", "블투"),
            List.of("게이밍", "gaming", "게임"),
            List.of("기계식", "mechanical"),
            List.of("노이즈캔슬링", "노캔", "anc"),
            List.of("헤드셋", "headset", "헤드폰", "headphone", "headphones"),
            List.of("이어폰", "earphone", "earphones", "이어버드", "earbuds"),
            List.of("스피커", "speaker", "speakers"),
            List.of("키보드", "keyboard"),
            List.of("마우스", "mouse"),
            List.of("모니터", "monitor"),
            List.of("usb-c", "usbc", "c타입", "타입c", "type-c"),
            List.of("4k", "uhd"));
    private static final Map<String, List<String>> SPELLING_OF = SPELLINGS.stream()
            .flatMap(group -> group.stream().map(w -> Map.entry(w, group)))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

    private SearchText() {
    }

    public static String build(String name, String description, String detail, Map<String, String> specs) {
        return build(name, description, detail, specs, List.of());
    }

    /**
     * @param tags AI가 붙인 검색 태그. 상세 설명이 길어 {@link #MAX_LENGTH}에서 잘려도 남도록 이름 바로 뒤에 둔다
     */
    public static String build(String name, String description, String detail, Map<String, String> specs,
                               List<String> tags) {
        Stream<String> parts = Stream.concat(Stream.concat(Stream.of(name), tags == null ? Stream.empty() : tags.stream()),
                Stream.of(description, detail));
        if (specs != null) {
            parts = Stream.concat(parts, specs.entrySet().stream().flatMap(e -> Stream.of(e.getKey(), e.getValue())));
        }
        String joined = parts.filter(s -> s != null && !s.isBlank())
                .flatMap(s -> words(s).stream())
                .collect(Collectors.joining(" "));
        String text = " " + joined + " ";
        return text.length() <= MAX_LENGTH ? text : text.substring(0, MAX_LENGTH - 1) + " ";
    }

    /** 검색어를 단어로 나눈다 (소문자, 구두점 제거) */
    public static List<String> words(String text) {
        if (text == null) return List.of();
        return Arrays.stream(SEPARATORS.split(text.toLowerCase(Locale.ROOT)))
                .filter(w -> !w.isEmpty())
                .toList();
    }

    /** 한글 한 글자처럼 그것만으로는 상품을 좁히기 어려운 단어. 모든 단어가 맞아야 하는 검색에서는 필수로 보지 않는다 */
    public static boolean isWeak(String word) {
        return word.length() == 1 && HANGUL.matcher(word).matches();
    }

    /** 단어와, 표기 차이 사전에서 같은 단어로 보는 말들 (첫 번째가 원래 단어) */
    public static List<String> variants(String word) {
        List<String> group = SPELLING_OF.get(word);
        if (group == null) return List.of(word);
        return Stream.concat(Stream.of(word), group.stream().filter(w -> !w.equals(word))).toList();
    }

    /** 단어 하나를 SQL LIKE 패턴으로. 패턴 중 하나라도 맞으면 그 단어가 맞은 것이다 */
    public static WordPattern pattern(String word) {
        String w = escape(word);
        if (isWeak(word)) {
            return new WordPattern(null, "% " + w + " %");
        }
        String prefix = word.length() >= 3 && HANGUL.matcher(word.substring(0, 2)).replaceAll("").isEmpty()
                ? "% " + escape(word.substring(0, 2)) + "%"
                : null;
        return new WordPattern("%" + w + "%", prefix);
    }

    /**
     * @param anywhere   띄어쓰기를 뺀 검색 문자열에 대한 패턴 (null이면 쓰지 않음)
     * @param wordStart  공백이 있는 검색 문자열에 대한 패턴 (null이면 쓰지 않음)
     */
    public record WordPattern(String anywhere, String wordStart) {
    }

    /** 단어(또는 표기가 다른 같은 단어)가 상품에 있는지. {@link #pattern}과 같은 규칙을 자바로 */
    public static boolean matchesAny(String searchText, String word) {
        return variants(word).stream().anyMatch(v -> matches(searchText, v));
    }

    /** {@link #pattern}과 같은 규칙을 자바로. 이미 DB에서 걸러 온 상품의 점수를 셀 때 쓴다 */
    public static boolean matches(String searchText, String word) {
        if (searchText == null) return false;
        if (isWeak(word)) return searchText.contains(" " + word + " ");
        if (searchText.replace(" ", "").contains(word)) return true;
        return word.length() >= 3 && HANGUL.matcher(word.substring(0, 2)).replaceAll("").isEmpty()
                && searchText.contains(" " + word.substring(0, 2));
    }

    /** LIKE의 이스케이프 문자는 '!'. 사용자 입력의 % _ 는 와일드카드가 아니라 글자로 취급한다 */
    public static String escape(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
