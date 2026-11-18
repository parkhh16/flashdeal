package com.flashdeal.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashdeal.common.config.FlashDealProperties;
import com.flashdeal.product.ProductCategory;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * 실제 Gemini 대신 가짜 서버(MockRestServiceServer)를 세워서, 키 없이 요청 모양과 응답·오류 처리를 검증한다.
 */
class GeminiQueryParserTest {

    private static final String URL = GeminiQueryParser.BASE_URL + GeminiQueryParser.PATH;
    private static final FlashDealProperties.Ai CONFIG =
            new FlashDealProperties.Ai("test-key", "gemini-3.5-flash-lite", Duration.ofSeconds(3));

    private MockRestServiceServer gemini;
    private GeminiQueryParser parser;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        gemini = MockRestServiceServer.bindTo(builder).build();
        parser = new GeminiQueryParser(CONFIG, builder, new ObjectMapper());
    }

    /**
     * 모델이 돌려준 JSON 글자를 Interactions API 응답에 담는다.
     * 실제 응답(2026-11, gemini-3.5-flash-lite)과 같은 모양: steps에 thought 단계가 먼저 오고, 그다음 model_output
     */
    private static String interaction(String modelText) throws Exception {
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
    @DisplayName("요청: 키 헤더, 모델, 시스템 지시, 검색어, JSON 스키마(카테고리 enum), 저장 안 함")
    void requestShape() throws Exception {
        gemini.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(jsonPath("$.model").value("gemini-3.5-flash-lite"))
                .andExpect(jsonPath("$.input").value("5만원 이하 조용한 키보드"))
                .andExpect(jsonPath("$.system_instruction").isNotEmpty())
                .andExpect(jsonPath("$.response_format.mime_type").value("application/json"))
                .andExpect(jsonPath("$.response_format.schema.properties.category.enum", hasItem("KEYBOARD")))
                .andExpect(jsonPath("$.store").value(false))
                .andRespond(withSuccess(interaction("""
                        {"keyword":"저소음","category":"KEYBOARD","minPrice":0,"maxPrice":50000}"""),
                        MediaType.APPLICATION_JSON));

        SearchFilter f = parser.parse("5만원 이하 조용한 키보드").orElseThrow();

        assertThat(f.keyword()).isEqualTo("저소음");
        assertThat(f.category()).isEqualTo(ProductCategory.KEYBOARD);
        assertThat(f.minPrice()).as("0은 조건 없음").isNull();
        assertThat(f.maxPrice()).isEqualTo(50_000);
        gemini.verify();
    }

    @Test
    @DisplayName("ANY와 빈 키워드는 조건 없음으로, 범위를 벗어난 가격은 sanitized()에서 버린다")
    void anyAndSanitize() throws Exception {
        gemini.expect(requestTo(URL)).andRespond(withSuccess(interaction("""
                {"keyword":"","category":"ANY","minPrice":-5,"maxPrice":999999999999}"""), MediaType.APPLICATION_JSON));

        SearchFilter f = parser.parse("아무거나").orElseThrow();

        assertThat(f.keyword()).isNull();
        assertThat(f.category()).isNull();
        assertThat(f.minPrice()).isNull();
        assertThat(f.maxPrice()).isNull();
    }

    @Test
    @DisplayName("모델이 키워드에 카테고리 단어를 섞어도('무선 마우스') 단어 단위로 빼서 '무선'만 남긴다")
    void categoryWordRemovedFromKeyword() throws Exception {
        gemini.expect(requestTo(URL)).andRespond(withSuccess(interaction("""
                {"keyword":"무선 마우스","category":"MOUSE","minPrice":0,"maxPrice":0}"""), MediaType.APPLICATION_JSON));

        assertThat(parser.parse("선 없는 마우스").orElseThrow().keyword()).isEqualTo("무선");
        assertThat(GeminiQueryParser.withoutCategoryWord("마우스패드", ProductCategory.MOUSE))
                .as("단어 일부는 건드리지 않는다").isEqualTo("마우스패드");
        assertThat(GeminiQueryParser.withoutCategoryWord("게이밍 헤드셋", ProductCategory.AUDIO))
                .as("좁은 단어는 남긴다").isEqualTo("게이밍 헤드셋");
    }

    @Test
    @DisplayName("모델이 깨진 JSON을 주면 empty → 호출자가 규칙 기반 파서로 대체")
    void brokenJson() throws Exception {
        gemini.expect(requestTo(URL)).andRespond(withSuccess(interaction("키보드 찾으시는군요!"), MediaType.APPLICATION_JSON));

        assertThat(parser.parse("키보드")).isEmpty();
    }

    @Test
    @DisplayName("model_output 단계가 없으면(안전 차단 등) empty")
    void noModelOutput() {
        gemini.expect(requestTo(URL)).andRespond(withSuccess("""
                {"status":"completed","steps":[]}""", MediaType.APPLICATION_JSON));

        assertThat(parser.parse("키보드")).isEmpty();
    }

    @Test
    @DisplayName("서버 오류(500), 한도 초과(429), 시간 초과는 예외 → AiSearchService가 폴백한다")
    void errorsAreThrown() {
        gemini.expect(requestTo(URL)).andRespond(withServerError());
        assertThatThrownBy(() -> parser.parse("키보드")).isInstanceOf(HttpServerErrorException.class);

        gemini.reset();
        gemini.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> parser.parse("키보드")).isInstanceOf(HttpClientErrorException.class);

        gemini.reset();
        gemini.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("read timed out")));
        assertThatThrownBy(() -> parser.parse("키보드")).isInstanceOf(ResourceAccessException.class);
    }

    @Test
    @DisplayName("키가 비어 있으면 꺼진 상태 (호출하지 않고 규칙 기반 파서만 쓴다)")
    void disabledWithoutKey() {
        var noKey = new GeminiQueryParser(new FlashDealProperties.Ai(" ", "m", Duration.ofSeconds(1)),
                RestClient.builder(), new ObjectMapper());

        assertThat(noKey.isEnabled()).isFalse();
        assertThat(parser.isEnabled()).isTrue();
    }
}
