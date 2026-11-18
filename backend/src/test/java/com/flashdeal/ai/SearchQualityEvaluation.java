package com.flashdeal.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashdeal.product.ProductDtos.ProductResponse;
import com.flashdeal.product.ProductRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검색 품질 평가. {@code ./gradlew searchEval}로만 실행되고 일반 테스트에서는 돌지 않는다.
 *
 * <p>사용자가 검색창에 치는 것과 같은 경로({@link AiSearchService#search(String)}: 파싱 → 검색 → 오타 교정 → 완화)를
 * 시드 상품 25개에 대해 돌리고, 질의마다 미리 정해 둔 정답 상품과 비교한다. 테스트 프로필은 API 키가 비어 있어서
 * LLM을 부르지 않는다 (검색할 때 AI를 쓰지 않는 구조의 품질을 잰다).
 * <ul>
 *   <li>◎ 정확: 결과가 1개 이상이고 모두 정답 (정답이 "결과 없음"이면 결과가 비어야 함)</li>
 *   <li>△ 포함: 정답이 하나라도 결과에 들어 있음 (정답 외 상품이 섞여도 됨)</li>
 * </ul>
 * 리포트: {@code build/reports/search-eval/<label>.md} ({@code -Plabel=이름}, 기본 latest)
 */
@Tag("search-eval")
@SpringBootTest(properties = "flashdeal.seed.enabled=true")
@ActiveProfiles("test")
class SearchQualityEvaluation {

    private static final String ANY = "*";

    record Case(String set, String tag, String q, List<String> expect) {
    }

    record Graded(Case c, List<String> results, boolean exact, boolean hit) {
        String mark() {
            return exact ? "◎" : hit ? "△" : "X";
        }
    }

    @Autowired AiSearchService service;
    @Autowired ProductRepository productRepository;
    @Autowired ObjectMapper objectMapper;

    @Test
    void evaluate() throws Exception {
        List<Case> cases = loadCases();
        Set<String> seedNames = productRepository.findAll().stream().map(p -> p.getName()).collect(Collectors.toSet());
        assertThat(seedNames).as("시드 상품").hasSize(25);
        cases.forEach(c -> c.expect().stream().filter(e -> !ANY.equals(e))
                .forEach(e -> assertThat(seedNames).as("정답 상품명 오타: " + c.q()).contains(e)));

        List<Graded> graded = cases.stream().map(this::grade).toList();
        String report = report(graded);
        System.out.println(report);

        String label = System.getProperty("search.eval.label", "latest");
        Path out = Path.of("build/reports/search-eval/" + label + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);
        System.out.println("리포트: " + out.toAbsolutePath());
    }

    private Graded grade(Case c) {
        List<String> results = service.search(c.q()).products().stream().map(ProductResponse::name).toList();
        boolean any = c.expect().contains(ANY);
        boolean exact;
        boolean hit;
        if (c.expect().isEmpty()) {
            exact = hit = results.isEmpty();
        } else {
            exact = !results.isEmpty() && (any || c.expect().containsAll(results));
            hit = any ? !results.isEmpty() : results.stream().anyMatch(c.expect()::contains);
        }
        return new Graded(c, results, exact, hit);
    }

    private String report(List<Graded> graded) {
        StringBuilder sb = new StringBuilder("# 검색 품질 평가\n\n");
        sb.append("| 묶음 | 질의 수 | ◎ 정확 | △ 포함 | X 실패 |\n|---|---|---|---|---|\n");
        Map<String, List<Graded>> bySet = graded.stream()
                .collect(Collectors.groupingBy(g -> g.c().set(), LinkedHashMap::new, Collectors.toList()));
        bySet.forEach((set, list) -> sb.append(row(set, list)));
        sb.append(row("전체", graded)).append('\n');

        sb.append("## 태그별\n\n| 태그 | 질의 수 | ◎ | △ |\n|---|---|---|---|\n");
        graded.stream().collect(Collectors.groupingBy(g -> g.c().set() + " / " + g.c().tag(), LinkedHashMap::new, Collectors.toList()))
                .forEach((tag, list) -> sb.append("| ").append(tag).append(" | ").append(list.size()).append(" | ")
                        .append(count(list, true)).append(" | ").append(count(list, false)).append(" |\n"));

        sb.append("\n## 질의별\n\n| | 묶음 | 질의 | 결과 수 | 결과 (5개까지) |\n|---|---|---|---|---|\n");
        for (Graded g : graded) {
            String shown = g.results().stream().limit(5).collect(Collectors.joining(", "));
            sb.append("| ").append(g.mark()).append(" | ").append(g.c().set()).append(" | ").append(g.c().q())
                    .append(" | ").append(g.results().size()).append(" | ").append(shown.isEmpty() ? "(없음)" : shown).append(" |\n");
        }
        return sb.toString();
    }

    private static String row(String name, List<Graded> list) {
        long exact = count(list, true);
        long hit = count(list, false);
        return "| " + name + " | " + list.size() + " | " + exact + " | " + hit + " | " + (list.size() - hit) + " |\n";
    }

    private static long count(List<Graded> list, boolean exact) {
        return list.stream().filter(g -> exact ? g.exact() : g.hit()).count();
    }

    private List<Case> loadCases() throws Exception {
        try (InputStream in = new ClassPathResource("search-eval/cases.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            List<Case> cases = new ArrayList<>();
            for (JsonNode n : root.get("cases")) {
                List<String> expect = new ArrayList<>();
                n.get("expect").forEach(e -> expect.add(e.asText()));
                cases.add(new Case(n.get("set").asText(), n.get("tag").asText(), n.get("q").asText(), expect));
            }
            return cases;
        }
    }
}
