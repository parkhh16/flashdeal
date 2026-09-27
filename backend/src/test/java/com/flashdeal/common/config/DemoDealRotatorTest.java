package com.flashdeal.common.config;

import com.flashdeal.order.OrderDtos.CreateOrderRequest;
import com.flashdeal.order.OrderDtos.OrderResponse;
import com.flashdeal.order.OrderCancelService;
import com.flashdeal.order.OrderFacade;
import com.flashdeal.product.DealStatus;
import com.flashdeal.product.Product;
import com.flashdeal.product.ProductRepository;
import com.flashdeal.product.SubCategory;
import com.flashdeal.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 시드 특가가 끝나도 시연용 특가가 사라지지 않게 재편성하되, 재고 정합성을 깨지 않는 조건에서만 한다 */
@SpringBootTest(properties = "flashdeal.demo.rotate-deals=true")
@ActiveProfiles("test")
@Import(TestFixtures.class)
class DemoDealRotatorTest {

    static final String KEYBOARD = "[한정 100개] 무접점 기계식 키보드 한정판";

    @Autowired DemoDealRotator rotator;
    @Autowired ProductRepository productRepository;
    @Autowired OrderFacade orderFacade;
    @Autowired OrderCancelService cancelService;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        fixtures.clean();
        jdbc.update("delete from product where name = ?", KEYBOARD);
    }

    private Product keyboardDeal(LocalDateTime start, LocalDateTime end, int stock) {
        return productRepository.save(Product.builder().name(KEYBOARD).subCategory(SubCategory.KEYBOARD_MECHANICAL)
                .price(89_000).originalPrice(159_000L).stock(stock).dealQuantity(100)
                .dealStartAt(start).dealEndAt(end).build());
    }

    private Product reload(Product p) {
        return productRepository.findById(p.getId()).orElseThrow();
    }

    @Test
    @DisplayName("끝난 지 15분이 지난 시드 특가는 같은 시간표(지금-1시간 ~ 지금+6시간, 재고 100)로 다시 편성된다")
    void rotatesEndedDeal() {
        LocalDateTime now = LocalDateTime.now();
        Product p = keyboardDeal(now.minusHours(8), now.minusHours(1), 3);

        assertThat(rotator.rotate()).isEqualTo(1);

        Product after = reload(p);
        assertThat(after.getStock()).isEqualTo(100);
        assertThat(Duration.between(now.minusHours(1), after.getDealStartAt()).abs()).isLessThan(Duration.ofMinutes(1));
        assertThat(Duration.between(now.plusHours(6), after.getDealEndAt()).abs()).isLessThan(Duration.ofMinutes(1));
        assertThat(after.dealStatus(LocalDateTime.now())).isEqualTo(DealStatus.ONGOING);
        assertThat(rotator.rotate()).as("이미 새 회차가 진행 중이면 다시 건드리지 않는다").isZero();
    }

    @Test
    @DisplayName("막 끝난 특가(15분 이내)는 건드리지 않는다 - 종료 직전 주문의 처리가 끝날 시간을 준다")
    void graceAfterEnd() {
        LocalDateTime now = LocalDateTime.now();
        Product p = keyboardDeal(now.minusHours(7), now.minusMinutes(5), 3);

        assertThat(rotator.rotate()).isZero();
        assertThat(reload(p).getStock()).isEqualTo(3);
    }

    @Test
    @DisplayName("결제 대기 주문이 재고를 쥐고 있으면 재편성하지 않는다 - 덮어쓴 뒤 그 주문이 만료되면 재고가 부풀기 때문")
    void skipWhileStockIsHeld() {
        LocalDateTime now = LocalDateTime.now();
        Product p = keyboardDeal(now.minusHours(1), now.plusHours(1), 10);
        OrderResponse pending = orderFacade.create(1L, new CreateOrderRequest(List.of(new CreateOrderRequest.Line(p.getId(), 2))));
        jdbc.update("update product set deal_end_at = ? where id = ?", now.minusHours(1), p.getId()); // 특가가 끝남

        assertThat(rotator.rotate()).isZero();
        assertThat(reload(p).getStock()).isEqualTo(8);

        cancelService.cancel(pending.id()); // 결제 대기 주문 정리 → 재고 10으로 복구
        assertThat(rotator.rotate()).isEqualTo(1);
        assertThat(reload(p).getStock()).isEqualTo(100);
    }

    @Test
    @DisplayName("시간표에 없는 상품(관리자가 직접 만든 특가)은 끝나도 건드리지 않는다")
    void ignoresNonDemoDeals() {
        LocalDateTime now = LocalDateTime.now();
        Product other = productRepository.save(Product.builder().name("관리자가 만든 특가").subCategory(SubCategory.MOUSE_GAMING)
                .price(10_000).stock(5).dealStartAt(now.minusHours(5)).dealEndAt(now.minusHours(1)).build());

        rotator.rotate();

        assertThat(reload(other).getDealEndAt()).isBefore(now);
        assertThat(reload(other).getStock()).isEqualTo(5);
    }
}
