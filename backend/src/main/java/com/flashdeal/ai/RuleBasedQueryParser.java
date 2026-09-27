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

    private static final Pattern RANGE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(만|천)?\\s*원?\\s*[~\\-]\\s*(\\d+(?:\\.\\d+)?)\\s*(만|천)\\s*원?");
    private static final Pattern PRICE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(만|천)?\\s*원?\\s*(이하|까지|미만|아래|이상|부터|넘는|초과)");
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
        String rest = query;
        Long min = null, max = null;

        Matcher range = RANGE.matcher(rest);
        if (range.find()) {
            String unit = range.group(2) != null ? range.group(2) : range.group(4);
            min = toWon(range.group(1), unit);
            max = toWon(range.group(3), range.group(4));
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

        String keyword = rest.replaceAll("(추천|찾아줘|보여줘|해줘|좀|싼|저렴한|괜찮은|제품|상품|용|짜리|의)", " ")
                .replaceAll("\\s+", " ").strip();
        return new SearchFilter(keyword, category, min, max).sanitized();
    }

    private static long toWon(String number, String unit) {
        double n = Double.parseDouble(number);
        if ("만".equals(unit)) n *= 10_000;
        else if ("천".equals(unit)) n *= 1_000;
        return (long) n;
    }
}
