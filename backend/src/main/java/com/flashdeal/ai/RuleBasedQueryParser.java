package com.flashdeal.ai;

import com.flashdeal.product.ProductCategory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 장애 시 폴백 파서. "3만원 이하 키보드" 같은 정형화된 패턴만 처리한다.
 * LLM 없이도 서비스가 동작해야 한다는 원칙(Graceful Degradation)을 위한 최소 구현이다.
 */
@Component
public class RuleBasedQueryParser {

    /** "10~20만원", "4만원에서 6만원 사이", "3만원부터 5만원까지" */
    private static final Pattern RANGE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(만|천)?\\s*원?\\s*(?:[~\\-]|에서|부터)\\s*(\\d+(?:\\.\\d+)?)\\s*(만|천)?\\s*원?\\s*(?:사이|까지|이내)?");
    private static final Pattern PRICE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(만|천)?\\s*원?\\s*(이하|까지|미만|아래|이상|부터|넘는|초과)");
    /** 숫자 사이 쉼표: "35,000원" → "35000원" */
    private static final Pattern THOUSANDS_COMMA = Pattern.compile("(?<=\\d),(?=\\d{3})");
    /**
     * 한글로 쓴 금액: "오만원", "삼만오천원". 단어 중간("사이오만원"), 숫자 뒤("5만원"),
     * 원이 안 붙은 경우("오디오")는 건드리지 않는다
     */
    private static final Pattern KOREAN_AMOUNT = Pattern.compile("(?<![가-힣0-9.])([일이삼사오육칠팔구십백천만]+)(?=\\s*원)");
    private static final String KOREAN_DIGITS = "_일이삼사오육칠팔구";
    /**
     * 검색어에서 빼는 군더더기 단어. 단어 전체가 일치할 때만 뺀다.
     * 글자 단위로 지우면 "조용히"의 '용', "비싼"의 '싼'까지 사라진다.
     */
    private static final Pattern FILLER = Pattern.compile(
            "(추천|찾아|보여|알려)?해?(줘|주세요|줄래)?|좀|가장|제일|(싼|저렴한|괜찮은)(거|것)?|제품|상품|용|짜리|의|거|것"
                    + "|을|를|은|는|이|가|로|으로|에|도");
    /** "게임용" → "게임". 떼고 두 글자 이상 남을 때만 뗀다 ("사용", "전용"은 그대로) */
    private static final Pattern SUFFIX = Pattern.compile("(용|짜리|의)$");
    private static final Map<String, ProductCategory> CATEGORY_WORDS = new LinkedHashMap<>();
    /** 대분류(오디오, 액세서리) 안의 일부만 가리키는 단어 */
    private static final java.util.Set<String> NARROW_WORDS = java.util.Set.of("헤드셋", "이어폰", "스피커", "거치대", "패드", "케이블");

    static {
        CATEGORY_WORDS.put("키보드", ProductCategory.KEYBOARD);
        CATEGORY_WORDS.put("마우스", ProductCategory.MOUSE);
        CATEGORY_WORDS.put("모니터", ProductCategory.MONITOR);
        CATEGORY_WORDS.put("헤드셋", ProductCategory.AUDIO);
        CATEGORY_WORDS.put("이어폰", ProductCategory.AUDIO);
        CATEGORY_WORDS.put("스피커", ProductCategory.AUDIO);
        CATEGORY_WORDS.put("거치대", ProductCategory.ACCESSORY);
        CATEGORY_WORDS.put("패드", ProductCategory.ACCESSORY);
        CATEGORY_WORDS.put("케이블", ProductCategory.ACCESSORY);
    }

    public SearchFilter parse(String query) {
        String rest = normalizeAmounts(query);
        Long min = null, max = null;

        Matcher range = findPriceRange(rest);
        if (range != null) {
            // 한쪽에만 단위가 있으면 다른 쪽도 같은 단위로 본다 ("10~20만원", "4만~6")
            min = toWon(range.group(1), range.group(2) != null ? range.group(2) : range.group(4));
            max = toWon(range.group(3), range.group(4) != null ? range.group(4) : range.group(2));
            rest = rest.replace(range.group(), " ");
        } else {
            Matcher price = PRICE.matcher(rest);
            if (price.find()) {
                long won = toWon(price.group(1), price.group(2));
                if (price.group(3).matches("이하|까지|미만|아래")) max = won;
                else min = won;
                rest = rest.replace(price.group(), " ");
            }
        }

        ProductCategory category = null;
        for (var e : CATEGORY_WORDS.entrySet()) {
            if (rest.contains(e.getKey())) {
                category = e.getValue();
                // "헤드셋"은 오디오 전체가 아니라 헤드셋만 원하는 것이므로, 대분류보다 좁은 단어는 키워드로도 남긴다
                if (!NARROW_WORDS.contains(e.getKey())) {
                    rest = rest.replace(e.getKey(), " ");
                }
                break;
            }
        }
        if (category == null) {
            // 타이핑 중인 부분 입력("마우", "키보")도 카테고리로 인식한다. 두 글자 이상일 때만 (한 글자는 오탐이 많음)
            for (String token : rest.trim().split("\\s+")) {
                if (token.length() < 2) continue;
                var match = CATEGORY_WORDS.entrySet().stream().filter(e -> e.getKey().startsWith(token)).findFirst();
                if (match.isPresent()) {
                    category = match.get().getValue();
                    rest = rest.replace(token, " ");
                    break;
                }
            }
        }

        return new SearchFilter(cleanKeyword(rest), category, min, max).sanitized();
    }

    private static String cleanKeyword(String rest) {
        StringBuilder keyword = new StringBuilder();
        for (String token : rest.trim().split("\\s+")) {
            if (token.isEmpty() || FILLER.matcher(token).matches()) continue;
            Matcher suffix = SUFFIX.matcher(token);
            if (suffix.find() && suffix.start() >= 2) token = token.substring(0, suffix.start());
            keyword.append(' ').append(token);
        }
        return keyword.toString().strip();
    }

    /** 단위(만·천·원)가 하나도 없는 "502-3" 같은 모델명은 가격 범위로 보지 않는다 */
    private static Matcher findPriceRange(String text) {
        Matcher m = RANGE.matcher(text);
        while (m.find()) {
            if (m.group(2) != null || m.group(4) != null || m.group().contains("원")) return m;
        }
        return null;
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
