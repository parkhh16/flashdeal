package com.flashdeal.ai;

import com.flashdeal.product.ProductCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 실제 Claude API 키 없이 AI 검색의 제어 흐름(LLM 결과 사용 → 캐시 → 장애 시 폴백 → 스탬피드 방지)을 검증한다.
 * LLM 호출부(ClaudeQueryParser)만 스텁으로 바꾸고 나머지는 실제 빈을 쓴다.
 */
@SpringBootTest
@ActiveProfiles("test")
class AiSearchServiceTest {

    @MockitoBean ClaudeQueryParser claude;
    @Autowired AiSearchService service;

    @BeforeEach
    void setUp() {
        when(claude.isEnabled()).thenReturn(true);
    }

    @Test
    @DisplayName("LLM 결과를 쓰고, 같은 질의는 캐시에서 꺼내 LLM을 다시 호출하지 않는다")
    void llmThenCache() {
        String q = "llm-cache-" + UUID();
        when(claude.parse(q)).thenReturn(Optional.of(new SearchFilter("무선", ProductCategory.MOUSE, null, 50_000L)));

        AiSearchService.AiSearchResponse first = service.search(q);
        AiSearchService.AiSearchResponse second = service.search(q);

        assertThat(first.parsedBy()).isEqualTo(AiSearchService.ParsedBy.LLM);
        assertThat(first.filter().category()).isEqualTo(ProductCategory.MOUSE);
        assertThat(second.parsedBy()).isEqualTo(AiSearchService.ParsedBy.LLM_CACHED);
        verify(claude, times(1)).parse(q);
    }

    @Test
    @DisplayName("LLM이 예외(타임아웃 등)를 던지면 규칙 기반으로 폴백하고, 폴백 결과는 캐시하지 않는다")
    void fallbackNotCached() {
        String q = "3만원 이하 키보드 " + UUID();
        when(claude.parse(anyString())).thenThrow(new RuntimeException("timeout"));

        AiSearchService.AiSearchResponse first = service.search(q);
        service.search(q);

        assertThat(first.parsedBy()).isEqualTo(AiSearchService.ParsedBy.RULE_FALLBACK);
        assertThat(first.filter().maxPrice()).isEqualTo(30_000L);
        assertThat(first.filter().category()).isEqualTo(ProductCategory.KEYBOARD);
        verify(claude, times(2)).parse(q); // 캐시하지 않았으므로 다음 요청은 LLM을 다시 시도한다
    }

    @Test
    @DisplayName("캐시 스탬피드 방지: 같은 질의 20개가 동시에 와도 LLM은 한 번만 호출된다")
    void stampede() throws Exception {
        String q = "stampede-" + UUID();
        when(claude.parse(q)).thenAnswer(inv -> {
            Thread.sleep(300); // 느린 LLM
            return Optional.of(new SearchFilter(null, ProductCategory.AUDIO, null, null));
        });
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AiSearchService.AiSearchResponse>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return service.search(q);
            }));
        }
        start.countDown();
        for (var f : futures) assertThat(f.get().filter().category()).isEqualTo(ProductCategory.AUDIO);
        pool.shutdown();

        verify(claude, times(1)).parse(q);
    }

    private static String UUID() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
