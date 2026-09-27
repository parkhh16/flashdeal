package com.flashdeal.admin;

import com.flashdeal.mockpg.ChaosSettings;
import com.flashdeal.order.OrderExpiryScheduler;
import com.flashdeal.payment.PaymentReconciler;
import com.flashdeal.payment.PaymentRepository;
import com.flashdeal.product.stock.StockService;
import com.flashdeal.product.stock.StockStrategyType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 장애 주입, 전략 전환, 수동 배치 실행 등 데모와 실험용 관리자 API. ROLE_ADMIN만 접근할 수 있다.
 */
@Tag(name = "Admin")
@SecurityRequirement(name = "bearer")
@Validated
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final StockService stockService;
    private final ChaosSettings chaosSettings;
    private final PaymentReconciler paymentReconciler;
    private final OrderExpiryScheduler orderExpiryScheduler;
    private final PaymentRepository paymentRepository;

    @GetMapping("/stock-strategy")
    public Map<String, StockStrategyType> strategy() {
        return Map.of("strategy", stockService.currentStrategy());
    }

    @Operation(summary = "재고 차감 전략 전환 (부하 테스트 비교용)")
    @PutMapping("/stock-strategy/{type}")
    public Map<String, StockStrategyType> changeStrategy(@PathVariable StockStrategyType type) {
        stockService.changeStrategy(type);
        return Map.of("strategy", type);
    }

    @GetMapping("/chaos")
    public ChaosSettings.Snapshot chaos() {
        return chaosSettings.get();
    }

    @Operation(summary = "Mock PG 장애 주입 설정")
    @PutMapping("/chaos")
    public ChaosSettings.Snapshot updateChaos(@RequestBody ChaosSettings.Snapshot snapshot) {
        chaosSettings.set(snapshot);
        return snapshot;
    }

    @DeleteMapping("/chaos")
    public ChaosSettings.Snapshot resetChaos() {
        chaosSettings.reset();
        return chaosSettings.get();
    }

    @Operation(summary = "결제 대사 즉시 실행")
    @PostMapping("/reconcile")
    public PaymentReconciler.Result reconcile() {
        return paymentReconciler.reconcile();
    }

    @Operation(summary = "만료 주문 정리 즉시 실행")
    @PostMapping("/expire")
    public Map<String, Integer> expire() {
        return Map.of("expired", orderExpiryScheduler.expireOverdueOrders());
    }

    @Operation(summary = "정합성 리포트", description = "PAID 주문과 승인 결제 금액이 불일치하는 건을 조회한다")
    @GetMapping("/consistency-report")
    public List<PaymentRepository.MismatchRow> consistency() {
        return paymentRepository.findMismatches();
    }
}
