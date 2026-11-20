package com.flashdeal.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashdeal.activity.ActivityEventType;
import com.flashdeal.activity.ActivityRecorder;
import com.flashdeal.ai.DemandService.*;
import com.flashdeal.common.error.BusinessException;
import com.flashdeal.product.Product;
import com.flashdeal.product.ProductRepository;
import com.flashdeal.product.SubCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 판매자 등록 추천. Gemini 호출부만 스텁으로 바꾸고 나머지(집계, 검사, 다시 검색, 저장)는 실제 빈을 쓴다.
 */
@SpringBootTest
@ActiveProfiles("test")
class DemandServiceTest {

    @MockitoBean GeminiClient gemini;
    @Autowired DemandService service;
    @Autowired ActivityRecorder recorder;
    @Autowired ProductRepository productRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void setUp() {
        jdbc.execute("delete from activity_log");
        jdbc.execute("delete from demand_report");
        jdbc.execute("delete from order_item");
        jdbc.execute("delete from orders");
        jdbc.execute("delete from product");
        productRepository.save(Product.builder().name("블루투스 포터블 스피커").subCategory(SubCategory.AUDIO_SPEAKER)
                .price(49_000).stock(10).description("캠핑용 방수 스피커").specs(Map.of("방수", "IP67")).build());
        when(gemini.isEnabled()).thenReturn(true);
    }

    private void searched(String q, String match, int times) throws Exception {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("q", q);
        detail.put("results", match.equals("NONE") ? 0 : 1);
        detail.put("match", match);
        for (int i = 0; i < times; i++) {
            recorder.record(new ActivityRecorder.Entry(7L, ActivityEventType.SEARCH, "GET", "/api/products/search",
                    200, 10, "t", json.writeValueAsString(detail), LocalDateTime.now().minusDays(1)));
        }
    }

    private void aiGroups(Group... groups) {
        when(gemini.generate(anyString(), anyString(), anyMap(), eq(Groups.class)))
                .thenReturn(Optional.of(new Groups(List.of(groups))));
    }

    @Test
    @DisplayName("못 찾은 검색만(결과 없음·일부 일치·완화) 검색어별로 센다. 찾은 검색과 오래된 기록은 빼고, 띄어쓰기·대소문자는 하나로")
    void countsOnlyFailedSearches() throws Exception {
        searched("웹캠", "NONE", 3);
        searched("웹캠 ", "NONE", 1);
        searched("물 튀어도 되는 스피커", "SOME_WORDS", 2);
        searched("WEBCAM", "RELAXED", 1);
        searched("webcam", "RELAXED", 1);
        searched("무선 마우스", "ALL_WORDS", 5);
        recorder.record(new ActivityRecorder.Entry(7L, ActivityEventType.SEARCH, "GET", "/api/products/search",
                200, 10, "t", "{\"q\":\"웹캠\",\"match\":\"NONE\"}", LocalDateTime.now().minusDays(40)));

        DemandView view = service.view(30);

        assertThat(view.failed()).extracting(FailedQuery::query, FailedQuery::searches).containsExactly(
                org.assertj.core.groups.Tuple.tuple("웹캠", 4L),
                org.assertj.core.groups.Tuple.tuple("webcam", 2L),
                org.assertj.core.groups.Tuple.tuple("물 튀어도 되는 스피커", 2L));
        assertThat(view.report()).isNull();
        verify(gemini, never()).generate(anyString(), anyString(), anyMap(), any());
    }

    @Test
    @DisplayName("AI가 묶고 판매자 말로 바꿈 → 코드가 확인: 없는 검색어는 빼고, 횟수는 코드가 센 값, 판매자 말로 우리 상품을 다시 검색해 '등록 추천'과 '있는데 못 찾음'으로 나눔")
    void aiGroupsCodeChecks() throws Exception {
        searched("웹캠", "NONE", 4);
        searched("화상회의 카메라", "NONE", 2);
        searched("물 튀어도 되는 스피커", "SOME_WORDS", 3);
        aiGroups(
                new Group("웹캠", "OTHER", List.of("웹캠"), List.of("웹캠", "화상회의 카메라", "4K 웹캠 최저가")),   // 마지막은 목록에 없는 검색어
                new Group("물 튀어도 되는 스피커", "AUDIO", List.of("방수 스피커"), List.of("물 튀어도 되는 스피커 (3)", "웹캠")),    // 횟수를 붙여 돌려줘도 같은 검색어, 웹캠은 이미 앞 묶음에
                new Group("지어낸 수요", "OTHER", List.of("지어낸"), List.of("아무도 안 한 검색")));

        Report report = service.generate(30);

        assertThat(report.needs()).extracting(Need::need, Need::searches, Need::status).containsExactly(
                org.assertj.core.groups.Tuple.tuple("웹캠", 6L, Status.NEW),
                org.assertj.core.groups.Tuple.tuple("물 튀어도 되는 스피커", 3L, Status.EXISTING));
        assertThat(report.needs().get(0).queries()).containsExactly("웹캠", "화상회의 카메라");
        assertThat(report.needs().get(1).products()).containsExactly("블루투스 포터블 스피커");
        assertThat(report.failedSearches()).isEqualTo(9);
        assertThat(service.view(30).report().needs()).as("저장된 최근 결과").hasSize(2);
    }

    @Test
    @DisplayName("AI가 고른 종류와 다른 상품은 '있는 상품'으로 치지 않는다 ('마이크'에 '마이크로 스위치' 마우스가 걸려도 등록 추천)")
    void existingOnlyInSameCategory() throws Exception {
        productRepository.save(Product.builder().name("저소음 무선 마우스").subCategory(SubCategory.MOUSE_WIRELESS)
                .price(19_000).stock(10).description("조용한 클릭").specs(Map.of("스위치", "저소음 마이크로 스위치")).build());
        searched("콘덴서 마이크", "SOME_WORDS", 2);
        aiGroups(new Group("마이크", "AUDIO", List.of("마이크"), List.of("콘덴서 마이크")));

        Need need = service.generate(30).needs().get(0);

        assertThat(need.status()).isEqualTo(Status.NEW);
        assertThat(need.products()).isEmpty();
    }

    @Test
    @DisplayName("AI가 준 판매자 표현은 '이 중 아무거나'라서 하나씩 따로 검색한다 (합치면 저소음·무소음이 둘 다 있는 키보드를 찾게 됨)")
    void phrasesAreAlternatives() throws Exception {
        productRepository.save(Product.builder().name("저소음 무선 키보드").subCategory(SubCategory.KEYBOARD_WIRELESS)
                .price(29_000).stock(10).description("사무실용 저소음 적축").build());
        searched("밤에 써도 가족 안 깨는 키보드", "RELAXED", 2);
        aiGroups(new Group("저소음 키보드", "KEYBOARD", List.of("저소음 키보드", "무소음 키보드"),
                List.of("밤에 써도 가족 안 깨는 키보드")));

        Need need = service.generate(30).needs().get(0);

        assertThat(need.status()).isEqualTo(Status.EXISTING);
        assertThat(need.products()).containsExactly("저소음 무선 키보드");
    }

    @Test
    @DisplayName("판매자 표현으로 모두 맞는 상품이 없으면 '없다'고 단정하지 않고, 단어가 일부 맞는 같은 종류 상품을 후보로 보여 준다")
    void similarCandidatesWhenNew() throws Exception {
        searched("욕실에서 쓰는 스피커", "SOME_WORDS", 2);
        aiGroups(new Group("욕실 스피커", "AUDIO", List.of("욕실 스피커"), List.of("욕실에서 쓰는 스피커")));

        Need need = service.generate(30).needs().get(0);

        assertThat(need.status()).isEqualTo(Status.NEW);
        assertThat(need.similar()).containsExactly("블루투스 포터블 스피커");
    }

    @Test
    @DisplayName("후보는 표현의 맨 뒤 단어(상품 종류)가 맞아야 한다 ('USB 카메라'에 USB 허브는 후보가 아님)")
    void similarNeedsHeadWord() throws Exception {
        productRepository.save(Product.builder().name("USB-C 7in1 멀티 허브").subCategory(SubCategory.ACCESSORY_ETC)
                .price(39_000).stock(10).description("노트북 USB 확장").build());
        searched("웹캠", "NONE", 3);
        aiGroups(new Group("웹캠", "ACCESSORY", List.of("USB 카메라", "화상회의용 웹캠"), List.of("웹캠")));

        Need need = service.generate(30).needs().get(0);

        assertThat(need.status()).isEqualTo(Status.NEW);
        assertThat(need.similar()).isEmpty();
    }

    @Test
    @DisplayName("표현의 상품 종류가 상품 이름에 없으면 '있는 상품'이 아니라 후보 ('USB 마이크'의 단어가 헤드셋 스펙에 다 있어도 헤드셋은 마이크가 아님)")
    void headWordMustBeInName() throws Exception {
        productRepository.save(Product.builder().name("7.1채널 게이밍 헤드셋").subCategory(SubCategory.AUDIO_HEADSET)
                .price(69_000).stock(10).description("FPS용").specs(Map.of("마이크", "탈착식", "연결", "USB")).build());
        searched("방송용 마이크", "SOME_WORDS", 2);
        aiGroups(new Group("방송용 마이크", "AUDIO", List.of("USB 마이크"), List.of("방송용 마이크")));

        Need need = service.generate(30).needs().get(0);

        assertThat(need.status()).isEqualTo(Status.NEW);
        assertThat(need.products()).isEmpty();
        assertThat(need.similar()).containsExactly("7.1채널 게이밍 헤드셋");
    }

    @Test
    @DisplayName("키가 없으면 AI 정리는 409, 못 찾은 검색어 집계는 그대로 보여 준다")
    void withoutKey() throws Exception {
        when(gemini.isEnabled()).thenReturn(false);
        searched("웹캠", "NONE", 1);

        assertThatThrownBy(() -> service.generate(30)).isInstanceOf(BusinessException.class);
        DemandView view = service.view(30);
        assertThat(view.aiEnabled()).isFalse();
        assertThat(view.failed()).hasSize(1);
    }

    @Test
    @DisplayName("AI가 응답하지 않으면 503, 이전 결과는 그대로 남는다")
    void aiFailureKeepsPreviousReport() throws Exception {
        searched("웹캠", "NONE", 1);
        aiGroups(new Group("웹캠", "OTHER", List.of("웹캠"), List.of("웹캠")));
        service.generate(30);
        when(gemini.generate(anyString(), anyString(), anyMap(), eq(Groups.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("timeout"));

        assertThatThrownBy(() -> service.generate(30)).isInstanceOf(BusinessException.class);
        assertThat(service.view(30).report().needs()).extracting(Need::need).containsExactly("웹캠");
    }
}
