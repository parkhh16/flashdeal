package com.flashdeal.ai;

import com.flashdeal.product.ProductCategory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 규칙 기반 검색어 파서. 검색어를 카테고리·가격·제외 조건·키워드로 나눈다.
 * 표기 차이(띄어쓰기, 붙여 쓰기, 가격 표현, 영어 상품명)까지는 규칙으로 처리하고,
 * 뜻이 다른 표현("몰컴" → 저소음)은 다루지 않는다.
 */
@Component
public class RuleBasedQueryParser {

    /** "10~20만원", "4만원에서 6만원 사이", "3만원부터 5만원까지" */
    private static final Pattern RANGE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(만|천)?\\s*원?\\s*(?:[~\\-]|에서|부터)\\s*(\\d+(?:\\.\\d+)?)\\s*(만|천)?\\s*원?\\s*(?:사이|까지|이내)?");
    /** "5만원대" → 5만~5만9,999원, "10만원대" → 10만~19만9,999원 */
    private static final Pattern BAND = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(만|천)?\\s*원?\\s*대(?![가-힣])");
    /** 한쪽 조건. 두 개까지 읽는다: "20만원 이상 30만원 이하" */
    private static final Pattern PRICE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(만|천)?\\s*(원)?\\s*(이하|까지|미만|아래|안쪽|이내|이상|부터|넘는|넘게|초과)");
    private static final Pattern MAX_MARKER = Pattern.compile("이하|까지|미만|아래|안쪽|이내");
    /** "under 30000", "below 5만" */
    private static final Pattern ENGLISH_PRICE = Pattern.compile(
            "(?i)\\b(under|below|less than|over|above|more than)\\s*(\\d+(?:\\.\\d+)?)\\s*(만|천|k)?\\s*(원|won)?");
    /** 숫자 사이 쉼표: "35,000원" → "35000원" */
    private static final Pattern THOUSANDS_COMMA = Pattern.compile("(?<=\\d),(?=\\d{3})");
    /**
     * 한글로 쓴 금액: "오만원", "삼만오천원". 단어 중간("사이오만원"), 숫자 뒤("5만원"),
     * 원이 안 붙은 경우("오디오")는 건드리지 않는다
     */
    private static final Pattern KOREAN_AMOUNT = Pattern.compile("(?<![가-힣0-9.])([일이삼사오육칠팔구십백천만]+)(?=\\s*원)");
    private static final String KOREAN_DIGITS = "_일이삼사오육칠팔구";
    /** 제외 조건: "게이밍 말고", "유선 아닌", "RGB 빼고" */
    private static final Pattern EXCLUDE = Pattern.compile("(\\S+)\\s*(말고|빼고|아닌|아니고|제외하고|제외)(?=\\s|$)");
    /**
     * 검색어에서 빼는 군더더기 단어. 단어 전체가 일치할 때만 뺀다.
     * 글자 단위로 지우면 "조용히"의 '용', "비싼"의 '싼'까지 사라진다.
     */
    private static final Pattern FILLER = Pattern.compile(
            "(추천|찾아|보여|알려)?해?(줘|주세요|줄래)?|좀|가장|제일|(싼|저렴한|괜찮은)(거|것)?|제품|상품|용|짜리|의|거|것"
                    + "|을|를|은|는|이|가|로|으로|에|도|랑|이랑|와|과|하고|아무거나|아무거|아무|뭐든지|뭐든"
                    // 한 글자 기능어: 상품 설명의 "편하게 쓸 수 있습니다" 같은 문장과 맞아 엉뚱한 상품을 고르게 한다
                    + "|때|쓸|쓴|안|잘|못|더|덜|수|할|될|볼|갈|줄|틀|꼭|딱|걸");
    /** "게임용" → "게임". 떼고 두 글자 이상 남을 때만 뗀다 ("사용", "전용"은 그대로) */
    private static final Pattern SUFFIX = Pattern.compile("(용|짜리|의)$");

    /**
     * 상품 종류를 나타내는 말.
     *
     * @param canonical 키워드에 남길 때 쓰는 말 (영어로 쓴 경우 한글로)
     * @param narrow    대분류보다 좁은 종류("헤드셋"은 오디오 중 헤드셋만). 키워드에도 남겨서 그 종류만 찾는다
     */
    private record TypeWord(ProductCategory category, String canonical, boolean narrow) {
    }

    private static final Map<String, TypeWord> TYPE_WORDS = new LinkedHashMap<>();

    static {
        type(ProductCategory.KEYBOARD, false, "키보드", "keyboard");
        type(ProductCategory.MOUSE, false, "마우스", "mouse");
        type(ProductCategory.MONITOR, false, "모니터", "monitor");
        type(ProductCategory.AUDIO, true, "헤드셋", "headset");
        type(ProductCategory.AUDIO, true, "이어폰", "earphones", "earphone");
        type(ProductCategory.AUDIO, true, "스피커", "speakers", "speaker");
        type(ProductCategory.ACCESSORY, true, "거치대");
        type(ProductCategory.ACCESSORY, true, "패드");
        type(ProductCategory.ACCESSORY, true, "매트");
        type(ProductCategory.ACCESSORY, true, "케이블");
        type(ProductCategory.ACCESSORY, true, "허브");
        type(ProductCategory.ACCESSORY, true, "키캡");
    }

    private static void type(ProductCategory category, boolean narrow, String canonical, String... english) {
        TypeWord word = new TypeWord(category, canonical, narrow);
        TYPE_WORDS.put(canonical, word);
        for (String e : english) TYPE_WORDS.put(e, word);
    }

    private static final Set<String> REQUIRED_WORDS = Set.copyOf(TYPE_WORDS.values().stream()
            .filter(TypeWord::narrow).map(TypeWord::canonical).toList());

    /** 검색 결과에 꼭 있어야 하는 상품 종류 단어인지 ("이어폰"을 검색했으면 헤드셋이 아니라 이어폰이어야 한다) */
    public static boolean isRequiredWord(String word) {
        return REQUIRED_WORDS.contains(word);
    }

    public SearchFilter parse(String query) {
        String rest = splitCompounds(normalizeAmounts(query));
        Long min = null, max = null;

        Matcher range = findPriceRange(rest);
        Matcher band = range == null ? findWithUnit(BAND.matcher(rest)) : null;
        if (range != null) {
            // 한쪽에만 단위가 있으면 다른 쪽도 같은 단위로 본다 ("10~20만원", "4만~6")
            min = toWon(range.group(1), range.group(2) != null ? range.group(2) : range.group(4));
            max = toWon(range.group(3), range.group(4) != null ? range.group(4) : range.group(2));
            rest = rest.replace(range.group(), " ");
        } else if (band != null) {
            min = toWon(band.group(1), band.group(2));
            max = min + bandWidth(min) - 1;
            rest = rest.replace(band.group(), " ");
        } else {
            List<String> used = new ArrayList<>();
            Matcher price = PRICE.matcher(rest);
            while (price.find() && used.size() < 2) {
                // 단위도 "원"도 없는 작은 숫자("3 이하")는 가격이 아닐 가능성이 크다
                if (price.group(2) == null && price.group(3) == null && Double.parseDouble(price.group(1)) < 1_000) continue;
                long won = toWon(price.group(1), price.group(2));
                if (MAX_MARKER.matcher(price.group(4)).matches()) max = won;
                else min = won;
                used.add(price.group());
            }
            Matcher english = ENGLISH_PRICE.matcher(rest);
            if (used.isEmpty() && english.find()) {
                long won = toWon(english.group(2), "k".equalsIgnoreCase(english.group(3)) ? "천" : english.group(3));
                if (english.group(1).toLowerCase(Locale.ROOT).matches("under|below|less than")) max = won;
                else min = won;
                used.add(english.group());
            }
            for (String u : used) rest = rest.replace(u, " ");
        }

        List<String> excludes = new ArrayList<>();
        Matcher exclude = EXCLUDE.matcher(rest);
        while (exclude.find()) excludes.add(stripSuffix(exclude.group(1)));
        rest = EXCLUDE.matcher(rest).replaceAll(" ");

        // 상품 종류 단어가 여러 개면 맨 뒤의 것을 쓴다. 한국어 복합어는 뒤가 중심이다 ("마우스 패드"는 패드, "헤드셋 거치대"는 거치대)
        ProductCategory category = null;
        String lower = rest.toLowerCase(Locale.ROOT);
        int at = -1;
        String alias = null;
        for (String w : TYPE_WORDS.keySet()) {
            int i = lower.lastIndexOf(w);
            if (i > at || (i >= 0 && i == at && w.length() > alias.length())) {
                at = i;
                alias = w;
            }
        }
        if (alias != null) {
            TypeWord type = TYPE_WORDS.get(alias);
            category = type.category();
            // 좁은 종류는 키워드에 그대로 남기고(영어면 한글로), 대분류 이름(키보드·마우스·모니터)은 뺀다
            String replacement = type.narrow() ? type.canonical() : " ";
            rest = rest.substring(0, at) + replacement + rest.substring(at + alias.length());
        } else {
            // 타이핑 중인 부분 입력("마우", "키보")과 끝 글자만 틀린 오타("키보트", "모니타")도 상품 종류로 본다.
            // 두 글자 이상일 때만 (한 글자는 오탐이 많음)
            for (String token : rest.trim().split("\\s+")) {
                if (token.length() < 2) continue;
                var match = TYPE_WORDS.entrySet().stream()
                        .filter(e -> e.getKey().startsWith(token) || lastSyllableTypo(token, e.getKey()))
                        .findFirst();
                if (match.isPresent()) {
                    TypeWord type = match.get().getValue();
                    category = type.category();
                    rest = rest.replace(token, type.narrow() ? type.canonical() : " ");
                    break;
                }
            }
        }

        return new SearchFilter(cleanKeyword(rest), category, min, max, excludes).sanitized();
    }

    /**
     * 상품 종류 단어가 다른 말과 붙어 있으면 띄운다: "마우스패드" → "마우스 패드", "무선마우스" → "무선 마우스".
     * 남는 쪽이 한 글자면 띄우지 않는다: "장패드"(장 + 패드), "모니터랑"(모니터 + 조사)은 그대로
     */
    static String splitCompounds(String text) {
        StringBuilder out = new StringBuilder();
        for (String token : text.trim().split("\\s+")) {
            out.append(' ').append(splitToken(token, 0));
        }
        return out.toString().strip();
    }

    private static String splitToken(String token, int depth) {
        if (depth > 3) return token;
        for (String w : TYPE_WORDS.keySet()) {
            int i = token.indexOf(w);
            if (i < 0 || token.length() == w.length()) continue;
            String left = token.substring(0, i);
            String right = token.substring(i + w.length());
            if ((left.isEmpty() || left.length() >= 2) && (right.isEmpty() || right.length() >= 2)) {
                return (splitToken(left, depth + 1) + " " + w + " " + splitToken(right, depth + 1)).strip();
            }
        }
        return token;
    }

    /** 세 글자 이상 한글에서 마지막 글자만 다름: "키보트" ~ "키보드", "마우쓰" ~ "마우스" */
    static boolean lastSyllableTypo(String token, String word) {
        return token.length() >= 3 && token.length() == word.length() && !token.equals(word)
                && token.matches("[가-힣]+") && token.regionMatches(0, word, 0, word.length() - 1);
    }

    private static String cleanKeyword(String rest) {
        StringBuilder keyword = new StringBuilder();
        for (String token : rest.trim().split("\\s+")) {
            if (token.isEmpty() || FILLER.matcher(token).matches()) continue;
            keyword.append(' ').append(stripSuffix(token));
        }
        return keyword.toString().strip();
    }

    private static String stripSuffix(String token) {
        Matcher suffix = SUFFIX.matcher(token);
        return suffix.find() && suffix.start() >= 2 ? token.substring(0, suffix.start()) : token;
    }

    /** 단위(만·천·원)가 하나도 없는 "502-3" 같은 모델명은 가격 범위로 보지 않는다 */
    private static Matcher findPriceRange(String text) {
        Matcher m = RANGE.matcher(text);
        while (m.find()) {
            if (m.group(2) != null || m.group(4) != null || m.group().contains("원")) return m;
        }
        return null;
    }

    /** 단위나 "원"이 붙은 것만 가격으로 본다 ("10대 키보드"는 가격이 아니다) */
    private static Matcher findWithUnit(Matcher m) {
        while (m.find()) {
            if (m.group(2) != null || m.group().contains("원")) return m;
        }
        return null;
    }

    /** 가격대의 폭: 마지막으로 0이 아닌 자리. 50000 → 10000, 150000 → 10000, 100000 → 100000 */
    static long bandWidth(long won) {
        long width = 1;
        while (won % (width * 10) == 0) width *= 10;
        return width;
    }

    private static String normalizeAmounts(String query) {
        String text = THOUSANDS_COMMA.matcher(query).replaceAll("");
        return KOREAN_AMOUNT.matcher(text).replaceAll(m -> String.valueOf(koreanToNumber(m.group(1))));
    }

    /** "오만" → 50000, "삼만오천" → 35000, "십오만" → 150000, "만" → 10000 */
    static long koreanToNumber(String s) {
        long total = 0, section = 0, digit = 0;
        for (char c : s.toCharArray()) {
            int d = KOREAN_DIGITS.indexOf(c);
            if (d > 0) {
                digit = d;
            } else if (c == '만') {
                section += digit;
                total += (section == 0 ? 1 : section) * 10_000;
                section = 0;
                digit = 0;
            } else {
                int unit = c == '십' ? 10 : c == '백' ? 100 : 1_000;
                section += (digit == 0 ? 1 : digit) * unit;
                digit = 0;
            }
        }
        return total + section + digit;
    }

    private static long toWon(String number, String unit) {
        double n = Double.parseDouble(number);
        if ("만".equals(unit)) n *= 10_000;
        else if ("천".equals(unit)) n *= 1_000;
        return (long) n;
    }
}
