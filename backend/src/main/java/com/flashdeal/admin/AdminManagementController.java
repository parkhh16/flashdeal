package com.flashdeal.admin;

import com.flashdeal.admin.AdminCatalogService.*;
import com.flashdeal.order.OrderCancelService;
import com.flashdeal.order.OrderStatus;
import com.flashdeal.payment.PaymentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 운영용 관리자 API: 상품/특가 관리, 주문 관리(취소·환불), 정합성 점검. /api/admin/** 는 ROLE_ADMIN만 접근 */
@Tag(name = "Admin")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminManagementController {

    private final AdminCatalogService catalogService;
    private final OrderCancelService orderCancelService;
    private final AdminStockService adminStockService;
    private final PaymentRepository paymentRepository;

    public record StockAdjustment(int delta, String reason) {
    }

    @Operation(summary = "재고 조정 (입고 +N / 출고 -N)",
            description = "덮어쓰기가 아니라 증감이다. 결제 대기 주문이 쥔 수량이 나중에 돌아와도 실물 수와 어긋나지 않게 하기 위해서")
    @PostMapping("/products/{id}/stock-adjustments")
    public AdminStockService.Result adjustStock(@PathVariable Long id, @RequestBody StockAdjustment body) {
        return adminStockService.adjust(id, body.delta(), body.reason());
    }

    @Operation(summary = "상품 전체 (판매 중지 포함)")
    @GetMapping("/products")
    public List<AdminProduct> products() {
        return catalogService.products();
    }

    @Operation(summary = "상품 등록")
    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminProduct create(@Valid @RequestBody ProductForm form) {
        return catalogService.create(form);
    }

    @Operation(summary = "상품/특가 수정", description = "재고는 이 API로 바꾸지 않는다 (POST /products/{id}/stock-adjustments)")
    @PutMapping("/products/{id}")
    public AdminProduct update(@PathVariable Long id, @Valid @RequestBody ProductForm form) {
        return catalogService.update(id, form);
    }

    @Operation(summary = "판매 중지 / 재개")
    @PatchMapping("/products/{id}/active")
    public AdminProduct changeActive(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        return catalogService.changeActive(id, Boolean.TRUE.equals(body.get("active")));
    }

    @Operation(summary = "전체 주문")
    @GetMapping("/orders")
    public PageResponse<AdminOrder> orders(@RequestParam(required = false) OrderStatus status,
                                           @RequestParam(required = false) Long userId,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return catalogService.orders(status, userId, page, size);
    }

    @Operation(summary = "주문 취소", description = "결제 대기: 취소+재고 복구 / 결제 완료: PG 환불 성공 시 취소+재고 복구 / 결제 확인 중: 불가")
    @PostMapping("/orders/{id}/cancel")
    public Map<String, OrderStatus> cancel(@PathVariable Long id) {
        return Map.of("status", orderCancelService.cancel(id));
    }

    @Operation(summary = "정합성 리포트", description = "PAID 주문과 승인 결제 금액이 불일치하는 건을 조회한다")
    @GetMapping("/consistency-report")
    public List<PaymentRepository.MismatchRow> consistency() {
        return paymentRepository.findMismatches();
    }
}
