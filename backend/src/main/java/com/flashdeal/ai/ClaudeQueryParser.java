package com.flashdeal.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.flashdeal.common.config.FlashDealProperties;
import com.flashdeal.product.ProductCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Claude로 자연어 검색어를 "구조화된 필터"로만 바꾼다. 상품 데이터는 절대 LLM이 만들지 않고 DB에서 조회한다.
 * → 없는 상품이나 가격을 지어내는 환각이 사용자에게 노출될 경로가 없다.
 * Structured Outputs로 응답 스키마를 강제하고, 받은 값도 SearchFilter.sanitized()에서 다시 검증한다.
 */
@Slf4j
@Component
public class ClaudeQueryParser {

    private static final String SYSTEM_PROMPT = """
            You convert a Korean shopping search query into a product filter for an electronics flash-deal store.
            Categories: KEYBOARD, MOUSE, MONITOR, AUDIO (headsets, earphones, speakers), ACCESSORY (pads, stands, cables).
            Use ANY when no category is implied. Prices are in KRW: "3만원" = 30000, "5천원" = 5000.
            Use 0 for minPrice or maxPrice when there is no bound.
            keyword is a short product-name hint left after removing price and category words (e.g. "무선", "게이밍"), or "" if none.
            """;

    /** 모델 출력 스키마. Structured Outputs가 이 레코드에서 JSON Schema를 자동 생성한다 */
    public record LlmFilter(
            @JsonPropertyDescription("Short keyword hint, or empty string") String keyword,
            @JsonPropertyDescription("Product category, or ANY") LlmCategory category,
            @JsonPropertyDescription("Minimum price in KRW, 0 if none") long minPrice,
            @JsonPropertyDescription("Maximum price in KRW, 0 if none") long maxPrice) {
    }

    public enum LlmCategory {KEYBOARD, MOUSE, MONITOR, AUDIO, ACCESSORY, ANY}

    private final FlashDealProperties.Ai config;
    private final AnthropicClient client;

    public ClaudeQueryParser(FlashDealProperties properties) {
        this.config = properties.ai();
        this.client = isEnabled()
                ? AnthropicOkHttpClient.builder()
                .apiKey(config.apiKey())
                .timeout(config.timeout())
                .maxRetries(0) // 사용자가 기다리는 요청이므로 재시도 대신 즉시 폴백한다
                .build()
                : null;
    }

    public boolean isEnabled() {
        return config.apiKey() != null && !config.apiKey().isBlank();
    }

    /** @return 파싱 실패(거절, 빈 응답)면 empty. 네트워크/API 오류는 예외로 던져서 호출자가 폴백한다 */
    public Optional<SearchFilter> parse(String query) {
        StructuredMessageCreateParams<LlmFilter> params = MessageCreateParams.builder()
                .model(config.model())
                .maxTokens(1024L)
                .system(SYSTEM_PROMPT)
                .outputConfig(LlmFilter.class)
                // 안전 분류기가 거절하면 서버가 알아서 대체 모델로 라우팅한다
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .addUserMessage(query)
                .build();

        StructuredMessage<LlmFilter> response = client.messages().create(params);
        if (response.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            log.warn("event=AI_REFUSAL query={}", query);
            return Optional.empty();
        }
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .findFirst()
                .map(typed -> toFilter(typed.text()));
    }

    private SearchFilter toFilter(LlmFilter f) {
        ProductCategory category = f.category() == null || f.category() == LlmCategory.ANY
                ? null : ProductCategory.valueOf(f.category().name());
        return new SearchFilter(f.keyword(), category, f.minPrice(), f.maxPrice()).sanitized();
    }
}
