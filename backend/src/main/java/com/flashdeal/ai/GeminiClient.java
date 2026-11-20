package com.flashdeal.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashdeal.common.config.FlashDealProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Gemini(Interactions API) 호출. 응답 형식을 JSON Schema로 강제하고, 받은 JSON을 지정한 타입으로 읽는다.
 *
 * <p>손님이 기다리는 요청(검색)에서는 부르지 않는다. 상품 태그 만들기, 못 찾은 검색어 정리처럼
 * 백그라운드 작업에서만 쓴다. 실측 응답이 0.8~2.5초(2026-11-14)에서 7.5초(2026-11-18)까지 오갔기 때문이다.
 */
@Slf4j
@Component
public class GeminiClient {

    static final String BASE_URL = "https://generativelanguage.googleapis.com";
    static final String PATH = "/v1beta/interactions";

    /**
     * "모델이 붐빔(503)" 같은 서버 쪽 오류는 몇 초 뒤면 풀리는 경우가 많아서 그 자리에서 다시 시도한다.
     * 무료 등급에서 실제로 난 실패 23건이 모두 503 "high demand"였고, 수 초~수십 초 뒤에는 성공했다.
     * 한도 초과(429)와 시간 초과는 다시 시도하지 않는다: 429는 상대 한도를 존중해야 하고, 시간 초과는 이미 30초를 기다렸다
     */
    static final int MAX_ATTEMPTS = 3;
    /**
     * 이 시간이 지났으면 더 시도하지 않는다. 과부하가 길게 이어질 때(실측: 503 세 번, 또는 응답 없이 시간 초과) 재시도는
     * 기다림만 늘린다. 관리자가 버튼을 누르고 기다리는 시간을 30초 안팎으로 묶어 둔다
     */
    static final Duration RETRY_BUDGET = Duration.ofSeconds(10);

    private final FlashDealProperties.Ai config;
    private final RestClient http;
    private final ObjectMapper json;
    private final Duration retryDelay;

    @Autowired
    public GeminiClient(FlashDealProperties properties, RestClient.Builder builder, ObjectMapper json) {
        this(properties.ai(), builder.requestFactory(timeouts(properties.ai().timeout())), json, Duration.ofSeconds(2));
    }

    /** 테스트에서 가짜 서버(MockRestServiceServer)를 묶은 builder를 그대로 쓰기 위한 생성자. 재시도 대기는 테스트에서 0으로 */
    GeminiClient(FlashDealProperties.Ai config, RestClient.Builder builder, ObjectMapper json, Duration retryDelay) {
        this.config = config;
        this.json = json;
        this.retryDelay = retryDelay;
        this.http = builder.baseUrl(BASE_URL).build();
    }

    private static SimpleClientHttpRequestFactory timeouts(Duration read) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(read);
        return factory;
    }

    public boolean isEnabled() {
        return config.apiKey() != null && !config.apiKey().isBlank();
    }

    /**
     * @return 모델이 스키마대로 답했으면 그 값. 출력이 없거나(안전 차단 등) JSON이 깨졌으면 empty
     * @throws org.springframework.web.client.RestClientException 네트워크 오류, 시간 초과, 4xx·5xx (호출자가 다음 주기에 다시 시도)
     */
    public <T> Optional<T> generate(String instruction, String input, Map<String, Object> schema, Class<T> type) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("system_instruction", instruction);
        body.put("input", input);
        body.put("response_format", Map.of(
                "type", "text",
                "mime_type", "application/json",
                "schema", schema));
        body.put("store", false); // 보낸 내용을 Google 쪽에 대화 기록으로 남기지 않는다 (상태가 필요 없는 단발 호출)

        Optional<String> text = outputText(callWithRetry(body));
        if (text.isEmpty()) {
            log.warn("event=AI_EMPTY_OUTPUT");
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(text.get(), type));
        } catch (JsonProcessingException e) {
            log.warn("event=AI_BAD_JSON");
            return Optional.empty();
        }
    }

    /** 5xx면 2초, 4초 기다렸다가 최대 {@value #MAX_ATTEMPTS}번까지 (10초가 지났으면 그만). 그래도 안 되면 마지막 예외를 던진다 */
    private JsonNode callWithRetry(Map<String, Object> body) {
        long start = System.nanoTime();
        for (int attempt = 1; ; attempt++) {
            try {
                return http.post().uri(PATH)
                        .header("x-goog-api-key", config.apiKey())
                        .header("Api-Revision", "2026-05-20")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
            } catch (HttpServerErrorException e) {
                Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
                if (attempt >= MAX_ATTEMPTS || elapsed.compareTo(RETRY_BUDGET) > 0) throw e;
                Duration wait = retryDelay.multipliedBy(attempt);
                log.info("event=AI_RETRY attempt={} status={} waitMs={}", attempt, e.getStatusCode().value(), wait.toMillis());
                sleep(wait);
            }
        }
    }

    private static void sleep(Duration d) {
        if (d.isZero()) return;
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 응답의 steps 중 model_output 단계에서 text 내용을 꺼낸다.
     * 실제 응답은 steps: [{type: "thought", ...}, {type: "model_output", content: [{type: "text", text: "{...}"}]}]
     */
    static Optional<String> outputText(JsonNode response) {
        if (response == null) return Optional.empty();
        for (JsonNode step : response.path("steps")) {
            if (!"model_output".equals(step.path("type").asText())) continue;
            for (JsonNode content : step.path("content")) {
                if ("text".equals(content.path("type").asText()) && !content.path("text").asText().isBlank()) {
                    return Optional.of(content.path("text").asText());
                }
            }
        }
        return Optional.empty();
    }
}
