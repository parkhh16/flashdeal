package com.flashdeal.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record CreateOrderRequest(@NotEmpty @Size(max = 10) List<@Valid Line> items) {
        public record Line(@NotNull Long productId, @Min(1) @Max(10) int quantity) {
        }
    }

    public record OrderResponse(Long id, String orderNo, OrderStatus status, long totalAmount,
                                LocalDateTime expiresAt, LocalDateTime createdAt, List<Item> items) {

        public record Item(Long productId, String productName, long unitPrice, int quantity) {
        }

        public static OrderResponse from(Order order) {
            return new OrderResponse(order.getId(), order.getOrderNo(), order.getStatus(), order.getTotalAmount(),
                    order.getExpiresAt(), order.getCreatedAt(),
                    order.getItems().stream()
                            .map(i -> new Item(i.getProductId(), i.getProductName(), i.getUnitPrice(), i.getQuantity()))
                            .toList());
        }
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }
}
