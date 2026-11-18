package com.flashdeal.ai;

import com.flashdeal.ai.AiSearchService.AiSearchResponse;
import com.flashdeal.ai.AiSearchService.Match;
import com.flashdeal.product.Product;
import com.flashdeal.product.ProductDtos.ProductResponse;
import com.flashdeal.product.ProductDtos.SearchCondition;
import com.flashdeal.product.ProductDtos.Sort;
import com.flashdeal.product.ProductQueryService;
import com.flashdeal.product.ProductRepository;
import com.flashdeal.product.SubCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 없이 검색어를 처리하는 단어 검색: 상세 설명·스펙까지, 띄어쓰기·조사 차이, 상품 종류 단어, 일부 단어 일치, 제외, 완화 표시.
 * 테스트 프로필은 API 키가 비어 있어서 규칙 기반 파서만 쓴다.
 */
@SpringBootTest(properties = "flashdeal.ai.api-key=")
@ActiveProfiles("test")
class WordSearchTest {

    @Autowired AiSearchService service;
    @Autowired ProductQueryService productQueryService;
    @Autowired ProductRepository productRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("delete from order_item");
        jdbc.execute("delete from orders");
        jdbc.execute("delete from product");
        productRepository.saveAll(List.of(
                product("저소음 무선 키보드", SubCategory.KEYBOARD_WIRELESS, 29_000, "사무실용 저소음 적축",
                        "도서관처럼 조용한 사무실에서도 부담 없는 저소음 적축 키보드입니다.", specs("연결", "2.4GHz 무선")),
                product("무접점 기계식 키보드", SubCategory.KEYBOARD_MECHANICAL, 139_000, "정전용량 무접점",
                        "부드럽고 조용한 타건감을 제공합니다.", specs("연결", "USB-C 유선")),
                product("게이밍 텐키리스 키보드", SubCategory.KEYBOARD_GAMING, 54_000, "RGB 게이밍 키보드",
                        "빠른 입력이 필요한 FPS 게임을 위한 키보드입니다.", specs("폴링", "1000Hz")),
                product("대형 장패드", SubCategory.ACCESSORY_PAD, 12_900, "900x400 데스크 매트",
                        "키보드와 마우스를 모두 올릴 수 있는 대형 데스크 매트입니다.", specs("크기", "900x400mm")),
                product("무선 이어폰", SubCategory.AUDIO_EARPHONE, 39_000, "가성비 블루투스 이어폰",
                        "출퇴근길에 가볍게 쓰기 좋은 무선 이어폰입니다.", specs("방수", "IPX4")),
                product("노이즈캔슬링 무선 헤드셋", SubCategory.AUDIO_HEADSET, 99_000, "ANC 블루투스 헤드셋",
                        "액티브 노이즈캔슬링으로 주변 소음을 줄여 집중력을 높여줍니다.", specs("무게", "250g")),
                product("24인치 사무용 모니터", SubCategory.MONITOR_OFFICE, 129_000, "눈 편한 플리커프리",
                        "블루라이트 감소 모드를 지원하는 사무용 모니터입니다.", specs("주사율", "75Hz")),
                product("24인치 240Hz 게이밍 모니터", SubCategory.MONITOR_GAMING, 259_000, "e스포츠용 240Hz",
                        "0.5ms 응답속도로 e스포츠 환경에 최적화된 모니터입니다.", specs("주사율", "240Hz"))));
    }

    @Test
    @DisplayName("상세 설명까지 찾는다: '조용한 키보드' → 상세에 '조용한'이 있는 두 키보드")
    void searchesDetail() {
        AiSearchResponse r = service.search("조용한 키보드");

        assertThat(names(r)).containsExactlyInAnyOrder("저소음 무선 키보드", "무접점 기계식 키보드");
        assertThat(r.match()).isEqualTo(Match.ALL_WORDS);
    }

    @Test
    @DisplayName("조사·어미가 달라도 맞고, 단어가 모두 맞는 상품만: '사무실에서 조용히 쓸 키보드' → 저소음 키보드")
    void allWordsWithEndings() {
        AiSearchResponse r = service.search("사무실에서 조용히 쓸 키보드");

        assertThat(names(r)).containsExactly("저소음 무선 키보드");
    }

    @Test
    @DisplayName("띄어쓰기·붙여 쓰기: '데스크매트', '마우스패드', '마우스 패드' 모두 장패드")
    void spacing() {
        assertThat(names(service.search("데스크매트"))).containsExactly("대형 장패드");
        assertThat(names(service.search("마우스패드"))).containsExactly("대형 장패드");
        assertThat(names(service.search("마우스 패드"))).containsExactly("대형 장패드");
    }

    @Test
    @DisplayName("상품 종류 단어는 필수: '주변 소리 막아주는 이어폰'은 '주변'이 맞는 헤드셋이 아니라 이어폰만")
    void typeWordIsRequired() {
        AiSearchResponse r = service.search("주변 소리 막아주는 이어폰");

        assertThat(names(r)).containsExactly("무선 이어폰");
        assertThat(r.match()).isEqualTo(Match.SOME_WORDS);
    }

    @Test
    @DisplayName("모두 맞는 상품이 없으면 가장 많이 맞는 상품: '눈 안 아픈 모니터' → '눈 편한' 사무용 모니터")
    void bestPartialMatch() {
        AiSearchResponse r = service.search("눈 안 아픈 모니터");

        assertThat(names(r)).containsExactly("24인치 사무용 모니터");
        assertThat(r.match()).isEqualTo(Match.SOME_WORDS);
    }

    @Test
    @DisplayName("제외: '게이밍 말고 키보드' → 게이밍이 들어간 키보드는 빠진다")
    void excludes() {
        assertThat(names(service.search("게이밍 말고 키보드")))
                .containsExactlyInAnyOrder("저소음 무선 키보드", "무접점 기계식 키보드");
    }

    @Test
    @DisplayName("표기 차이: 'wireless 키보드' = '무선 키보드'")
    void spelling() {
        assertThat(names(service.search("wireless 키보드"))).containsExactly("저소음 무선 키보드");
    }

    @Test
    @DisplayName("단어가 하나도 안 맞으면 카테고리만으로 완화하고, 완화한 결과라고 알려 준다")
    void relaxedIsMarked() {
        AiSearchResponse r = service.search("몰컴 키보드");

        assertThat(r.products()).hasSize(3);
        assertThat(r.match()).isEqualTo(Match.RELAXED);
    }

    @Test
    @DisplayName("목록 API의 키워드도 같은 규칙: 단어별로, 상세 설명까지")
    void listKeyword() {
        List<ProductResponse> result = productQueryService.search(
                new SearchCondition(null, null, null, null, false, Sort.PRICE_ASC, "조용한 키보드"));

        assertThat(result).extracting(ProductResponse::name)
                .containsExactly("저소음 무선 키보드", "무접점 기계식 키보드");
    }

    private static List<String> names(AiSearchResponse r) {
        return r.products().stream().map(ProductResponse::name).toList();
    }

    private static Product product(String name, SubCategory sub, long price, String description, String detail,
                                   Map<String, String> specs) {
        return Product.builder().name(name).subCategory(sub).price(price).stock(10)
                .description(description).detail(detail).specs(specs).build();
    }

    private static Map<String, String> specs(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
