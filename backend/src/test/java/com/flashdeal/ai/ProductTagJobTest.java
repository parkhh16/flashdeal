package com.flashdeal.ai;

import com.flashdeal.ai.AiSearchService.Match;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * 상품 태그 백그라운드 작업. Gemini 호출부만 스텁으로 바꾸고 나머지(검사, 저장, 검색)는 실제 빈을 쓴다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProductTagJobTest {

    @MockitoBean GeminiClient gemini;
    @Autowired ProductTagJob job;
    @Autowired ProductRepository productRepository;
    @Autowired AiSearchService search;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private Long mouseId;

    @BeforeEach
    void setUp() {
        jdbc.execute("delete from order_item");
        jdbc.execute("delete from orders");
        jdbc.execute("delete from product");
        mouseId = productRepository.save(Product.builder().name("초경량 게이밍 마우스").subCategory(SubCategory.MOUSE_GAMING)
                .price(59_000).stock(10).description("58g 무선 게이밍 마우스").detail("손목 부담이 적은 무게")
                .specs(Map.of("무게", "58g", "연결", "2.4GHz 무선")).build()).getId();
        when(gemini.isEnabled()).thenReturn(true);
    }

    private void aiAnswers(List<String> tags) {
        when(gemini.generate(anyString(), anyString(), anyMap(), eq(ProductTagger.Tags.class)))
                .thenReturn(Optional.of(new ProductTagger.Tags(tags)));
    }

    private Product mouse() {
        return productRepository.findById(mouseId).orElseThrow();
    }

    @Test
    @DisplayName("새 상품에 태그를 붙이고, 태그로 검색된다 (상품 정보에 없던 '가벼운'으로 찾음)")
    void tagsMakeProductFindable() {
        assertThat(search.search("가벼운 마우스").match()).as("태그 전: 카테고리만으로 완화").isEqualTo(Match.RELAXED);
        aiAnswers(List.of("가벼운", "fps", "가성비", "헤드셋겸용"));

        assertThat(job.tagPending()).isEqualTo(1);

        assertThat(mouse().getSearchTags()).as("가격·광고 표현과 다른 종류 이름은 코드가 뺐다").containsExactly("가벼운", "fps");
        assertThat(mouse().getTaggedAt()).isNotNull();
        assertThat(search.search("가벼운 마우스").match()).isEqualTo(Match.ALL_WORDS);
        assertThat(job.tagPending()).as("이미 태그가 있으면 다시 부르지 않는다").isZero();
    }

    @Test
    @DisplayName("Gemini가 실패하면(시간 초과 등) 태그 없이 두고 다음 주기에 다시 시도한다")
    void failureRetriedNextTime() {
        when(gemini.generate(anyString(), anyString(), anyMap(), eq(ProductTagger.Tags.class)))
                .thenThrow(new ResourceAccessException("read timed out"));

        assertThat(job.tagPending()).isZero();
        assertThat(mouse().getTaggedAt()).isNull();

        aiAnswers(List.of("가벼운"));
        assertThat(job.tagPending()).isEqualTo(1);
    }

    @Test
    @DisplayName("AI가 답하는 동안 관리자가 상품을 고치면 옛 내용의 태그는 저장하지 않는다")
    void productChangedWhileTagging() {
        when(gemini.generate(anyString(), anyString(), anyMap(), eq(ProductTagger.Tags.class))).thenAnswer(inv -> {
            tx.executeWithoutResult(s -> {
                Product p = mouse();
                p.update("유선 게이밍 마우스", p.getSubCategory(), p.getPrice(), null, "유선 마우스", p.getDetail(), null);
            });
            return Optional.of(new ProductTagger.Tags(List.of("가벼운")));
        });

        assertThat(job.tagPending()).isZero();
        assertThat(mouse().getTaggedAt()).as("고친 내용으로 다음 주기에 다시 만든다").isNull();
    }

    @Test
    @DisplayName("이름·설명을 고치면 태그를 다시 만들고, 가격만 고치면 그대로 둔다")
    void retagOnlyWhenContentChanges() {
        aiAnswers(List.of("가벼운"));
        job.tagPending();

        tx.executeWithoutResult(s -> {
            Product p = mouse();
            p.update(p.getName(), p.getSubCategory(), 49_000, null, p.getDescription(), p.getDetail(), null);
        });
        assertThat(mouse().getTaggedAt()).isNotNull();

        tx.executeWithoutResult(s -> {
            Product p = mouse();
            p.update(p.getName(), p.getSubCategory(), p.getPrice(), null, "58g 초경량", p.getDetail(), null);
        });
        assertThat(mouse().getTaggedAt()).isNull();
        assertThat(mouse().getSearchTags()).as("새 태그가 나올 때까지는 이전 태그로 검색된다").containsExactly("가벼운");
    }
}
