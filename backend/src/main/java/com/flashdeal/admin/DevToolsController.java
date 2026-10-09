package com.flashdeal.admin;

import com.flashdeal.product.stock.StockService;
import com.flashdeal.product.stock.StockStrategyType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 개발·측정 전용 API. {@code flashdeal.dev-tools.enabled=true}일 때만 등록되고 기본값은 꺼짐이다.
 *
 * 부하 테스트(loadtest/order-rush.mjs)가 서버를 재시작하지 않고 재고 전략 4가지를 차례로 비교하려고 쓴다.
 * 운영에서 켜져 있으면 관리자가 초과 판매가 나는 NAIVE 전략으로 바꿀 수 있으므로 반드시 꺼 둔다.
 * (켜는 방법: 환경변수 FLASHDEAL_DEV_TOOLS_ENABLED=true)
 */
@Tag(name = "Dev Tools")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/admin/stock-strategy")
@ConditionalOnProperty(name = "flashdeal.dev-tools.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DevToolsController {

    private final StockService stockService;

    @GetMapping
    public Map<String, StockStrategyType> strategy() {
        return Map.of("strategy", stockService.currentStrategy());
    }

    @Operation(summary = "재고 차감 전략 전환 (부하 테스트 비교용)")
    @PutMapping("/{type}")
    public Map<String, StockStrategyType> changeStrategy(@PathVariable StockStrategyType type) {
        stockService.changeStrategy(type);
        return Map.of("strategy", type);
    }
}
