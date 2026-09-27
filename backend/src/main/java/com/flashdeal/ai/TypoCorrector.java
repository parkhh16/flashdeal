package com.flashdeal.ai;

import com.flashdeal.product.ProductRepository;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * 한글 오타 교정 ("마으수" → "마우스").
 * 음절 단위로 비교하면 "으/우" 차이도 한 글자 전체가 틀린 것으로 보이므로, 초성·중성·종성(자모)으로 분해해서 편집거리를 잰다.
 * 사전은 카테고리 단어 + 판매 중인 상품명의 단어라서, "우리 가게에 있는 말"로만 교정한다.
 *
 * 오교정 방지:
 * - 검색 결과가 0건일 때만 호출된다 (AiSearchService). 정상 검색에는 개입하지 않는다
 * - 두 음절 이상 단어만, 허용 거리는 단어 자모 길이의 1/3 이하
 * - 교정 사실은 응답(originalQuery)으로 사용자에게 알린다
 */
@Component
public class TypoCorrector {

    private static final List<String> BASE_WORDS = List.of(
            "키보드", "마우스", "모니터", "헤드셋", "이어폰", "스피커", "거치대", "장패드", "패드", "케이블", "키캡", "허브");

    private final ProductRepository productRepository;
    /** 상품명은 자주 바뀌지 않으므로 5분 캐시. 키는 하나뿐이다 */
    private final LoadingCache<String, Set<String>> dictionary;

    public TypoCorrector(ProductRepository productRepository) {
        this.productRepository = productRepository;
        this.dictionary = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).build(k -> buildDictionary());
    }

    /** @return 교정된 질의. 바꿀 단어가 없으면 empty */
    public Optional<String> correct(String query) {
        Set<String> dict = dictionary.get("all");
        String[] tokens = query.trim().split("\\s+");
        boolean changed = false;
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.length() < 2 || !isHangul(token) || dict.contains(token)) continue;
            String best = closest(token, dict);
            if (best != null) {
                tokens[i] = best;
                changed = true;
            }
        }
        return changed ? Optional.of(String.join(" ", tokens)) : Optional.empty();
    }

    private String closest(String token, Set<String> dict) {
        int[] source = jamo(token);
        int limit = Math.max(1, source.length / 3);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String word : dict) {
            if (Math.abs(word.length() - token.length()) > 1) continue; // 음절 수가 크게 다르면 비교할 필요 없음
            int d = distance(source, jamo(word));
            // 거리가 같으면 기본 카테고리 단어를 우선한다 (가장 흔한 검색 의도)
            if (d <= limit && (d < bestDistance || (d == bestDistance && BASE_WORDS.contains(word)))) {
                best = word;
                bestDistance = d;
            }
        }
        return best;
    }

    private Set<String> buildDictionary() {
        Set<String> words = new HashSet<>(BASE_WORDS);
        productRepository.findAll().stream()
                .filter(p -> p.isActive())
                .flatMap(p -> Arrays.stream(p.getName().replaceAll("[\\[\\]()0-9a-zA-Z]", " ").split("\\s+")))
                .filter(w -> w.length() >= 2 && isHangul(w))
                .forEach(words::add);
        return words;
    }

    /** 한글 음절을 (초성, 중성, 종성) 코드 배열로 분해. 종성이 없으면 생략 */
    static int[] jamo(String word) {
        List<Integer> out = new ArrayList<>();
        for (char c : word.toCharArray()) {
            if (c >= 0xAC00 && c <= 0xD7A3) {
                int code = c - 0xAC00;
                out.add(1000 + code / 588);          // 초성
                out.add(2000 + (code % 588) / 28);   // 중성
                if (code % 28 != 0) out.add(3000 + code % 28); // 종성
            } else {
                out.add((int) c);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 레벤슈타인 거리 (두 줄 배열로 O(n) 메모리) */
    static int distance(int[] a, int[] b) {
        int[] prev = new int[b.length + 1];
        int[] cur = new int[b.length + 1];
        for (int j = 0; j <= b.length; j++) prev[j] = j;
        for (int i = 1; i <= a.length; i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length; j++) {
                int cost = a[i - 1] == b[j - 1] ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length];
    }

    private static boolean isHangul(String s) {
        return s.chars().allMatch(c -> c >= 0xAC00 && c <= 0xD7A3);
    }
}
