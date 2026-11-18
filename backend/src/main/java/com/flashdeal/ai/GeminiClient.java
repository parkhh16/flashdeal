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

    private final FlashDealProperties.Ai config;
    private final RestClient http;
    private final ObjectMapper json;

    @Autowired
    public GeminiClient(FlashDealProperties properties, RestClient.Builder builder, ObjectMapper json) {
        this(properties.ai(), builder.requestFactory(timeouts(properties.ai().timeout())), json);
    }

    /** 테스트에서 가짜 서버(MockRestServiceServer)를 묶은 builder를 그대로 쓰기 위한 생성자 */
    GeminiClient(FlashDealProperties.Ai config, RestClient.Builder builder, ObjectMapper json) {
        this.config = config;
        this.json = json;
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

        JsonNode response = http.post().uri(PATH)
                .header("x-goog-api-key", config.apiKey())
                .header("Api-Revision", "2026-05-20")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        Optional<String> text = outputText(response);
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
