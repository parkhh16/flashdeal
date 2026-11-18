package com.flashdeal.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashdeal.common.config.FlashDealProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * 실제 Gemini 대신 가짜 서버(MockRestServiceServer)를 세워서, 키 없이 요청 모양과 응답·오류 처리를 검증한다.
 */
class GeminiClientTest {

    private static final String URL = GeminiClient.BASE_URL + GeminiClient.PATH;
    private static final FlashDealProperties.Ai CONFIG =
            new FlashDealProperties.Ai("test-key", "gemini-3.5-flash-lite", Duration.ofSeconds(3));
    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("tags", Map.of("type", "array", "items", Map.of("type", "string"))),
            "required", List.of("tags"));

    record Tags(List<String> tags) {
    }

    private MockRestServiceServer gemini;
    private GeminiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        gemini = MockRestServiceServer.bindTo(builder).build();
        client = new GeminiClient(CONFIG, builder, new ObjectMapper());
    }

    /**
     * 모델이 돌려준 JSON 글자를 Interactions API 응답에 담는다.
     * 실제 응답(2026-11, gemini-3.5-flash-lite)과 같은 모양: steps에 thought 단계가 먼저 오고, 그다음 model_output
     */
    static String interaction(String modelText) throws Exception {
        var mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        root.put("status", "completed");
        root.put("object", "interaction");
        var steps = root.putArray("steps");
        steps.addObject().put("type", "thought").put("signature", "opaque");
        var step = steps.addObject();
        step.put("type", "model_output");
        step.putArray("content").addObject().put("type", "text").put("text", modelText);
        return mapper.writeValueAsString(root);
    }

    @Test
    @DisplayName("요청: 키 헤더, 모델, 지시문, 입력, JSON 스키마, 저장 안 함 → 스키마 모양 그대로 읽는다")
    void requestShape() throws Exception {
        gemini.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(jsonPath("$.model").value("gemini-3.5-flash-lite"))
                .andExpect(jsonPath("$.system_instruction").value("지시문"))
                .andExpect(jsonPath("$.input").value("저소음 무선 키보드"))
                .andExpect(jsonPath("$.response_format.mime_type").value("application/json"))
                .andExpect(jsonPath("$.response_format.schema.required[0]").value("tags"))
                .andExpect(jsonPath("$.store").value(false))
                .andRespond(withSuccess(interaction("""
                        {"tags":["조용한","사무실"]}"""), MediaType.APPLICATION_JSON));

        Tags tags = client.generate("지시문", "저소음 무선 키보드", SCHEMA, Tags.class).orElseThrow();

        assertThat(tags.tags()).containsExactly("조용한", "사무실");
        gemini.verify();
    }

    @Test
    @DisplayName("모델이 깨진 JSON을 주면 empty")
    void brokenJson() throws Exception {
        gemini.expect(requestTo(URL)).andRespond(withSuccess(interaction("태그를 만들어 볼게요!"), MediaType.APPLICATION_JSON));

        assertThat(client.generate("지시문", "키보드", SCHEMA, Tags.class)).isEmpty();
    }

    @Test
    @DisplayName("model_output 단계가 없으면(안전 차단 등) empty")
    void noModelOutput() {
        gemini.expect(requestTo(URL)).andRespond(withSuccess("""
                {"status":"completed","steps":[]}""", MediaType.APPLICATION_JSON));

        assertThat(client.generate("지시문", "키보드", SCHEMA, Tags.class)).isEmpty();
    }

    @Test
    @DisplayName("서버 오류(500), 한도 초과(429), 시간 초과는 예외 → 호출한 작업이 다음 주기에 다시 시도한다")
    void errorsAreThrown() {
        gemini.expect(requestTo(URL)).andRespond(withServerError());
        assertThatThrownBy(() -> client.generate("지시문", "키보드", SCHEMA, Tags.class))
                .isInstanceOf(HttpServerErrorException.class);

        gemini.reset();
        gemini.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> client.generate("지시문", "키보드", SCHEMA, Tags.class))
                .isInstanceOf(HttpClientErrorException.class);

        gemini.reset();
        gemini.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("read timed out")));
        assertThatThrownBy(() -> client.generate("지시문", "키보드", SCHEMA, Tags.class))
                .isInstanceOf(ResourceAccessException.class);
    }

    @Test
    @DisplayName("키가 비어 있으면 꺼진 상태 (AI 작업을 건너뛴다)")
    void disabledWithoutKey() {
        var noKey = new GeminiClient(new FlashDealProperties.Ai(" ", "m", Duration.ofSeconds(1)),
                RestClient.builder(), new ObjectMapper());

        assertThat(noKey.isEnabled()).isFalse();
        assertThat(client.isEnabled()).isTrue();
    }
}
