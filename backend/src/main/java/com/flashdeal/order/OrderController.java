package com.flashdeal.order;

import com.flashdeal.activity.ActivityContext;
import com.flashdeal.auth.AuthUser;
import com.flashdeal.idempotency.IdempotencyService;
import com.flashdeal.order.OrderDtos.CreateOrderRequest;
import com.flashdeal.order.OrderDtos.OrderResponse;
import com.flashdeal.order.OrderDtos.PageResponse;
import com.flashdeal.payment.PaymentService;
import com.flashdeal.payment.PaymentService.PaymentResponse;
import com.flashdeal.payment.PaymentStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Order")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderFacade orderFacade;
    private final OrderService orderService;
    private final PaymentService paymentService;
    private final IdempotencyService idempotencyService;

    @Operation(summary = "주문 생성 (재고 선점)", description = "Idempotency-Key 헤더 필수. 같은 키로 재요청하면 최초 응답을 그대로 반환한다.")
    @PostMapping
    public ResponseEntity<OrderResponse> create(@AuthenticationPrincipal AuthUser user,
                                                @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                @Valid @RequestBody CreateOrderRequest request) {
        ActivityContext.put("items", request.items().stream().map(l -> l.productId() + "x" + l.quantity()).toList());
        ResponseEntity<OrderResponse> response = idempotencyService.execute(user.id(), "POST:/api/orders", idempotencyKey,
                request, OrderResponse.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(orderFacade.create(user.id(), request)));
        ActivityContext.put("orderNo", response.getBody().orderNo());
        ActivityContext.put("amount", response.getBody().totalAmount());
        return response;
    }

    @Operation(summary = "결제", description = "200: 결제 완료, 202: PG 응답 지연으로 확인 중(대사 후 확정), 402: 거절")
    @PostMapping("/{orderId}/payment")
    public ResponseEntity<PaymentResponse> pay(@AuthenticationPrincipal AuthUser user,
                                               @RequestHeader("Idempotency-Key") String idempotencyKey,
                                               @PathVariable Long orderId) {
        ActivityContext.put("orderId", orderId);
        ResponseEntity<PaymentResponse> result = idempotencyService.execute(user.id(),
                "POST:/api/orders/" + orderId + "/payment", idempotencyKey, orderId, PaymentResponse.class, () -> {
                    PaymentResponse response = paymentService.pay(user.id(), orderId);
                    HttpStatus status = response.status() == PaymentStatus.APPROVED ? HttpStatus.OK : HttpStatus.ACCEPTED;
                    return ResponseEntity.status(status).body(response);
                });
        ActivityContext.put("paymentStatus", result.getBody().status());
        return result;
    }

    @Operation(summary = "내 주문 목록")
    @GetMapping
    public PageResponse<OrderResponse> myOrders(@AuthenticationPrincipal AuthUser user,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "10") int size) {
        return orderService.getMyOrders(user.id(), page, size);
    }

    @Operation(summary = "내 주문 상세", description = "다른 사람의 주문이면 404를 반환한다 (존재 여부 비노출)")
    @GetMapping("/{orderId}")
    public OrderResponse myOrder(@AuthenticationPrincipal AuthUser user, @PathVariable Long orderId) {
        return orderService.getMyOrder(user.id(), orderId);
    }
}
