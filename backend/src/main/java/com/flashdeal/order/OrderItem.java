package com.flashdeal.order;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품명과 가격은 주문 시점 값으로 스냅샷을 남긴다.
 * 상품 가격이 바뀌어도 주문 내역은 바뀌면 안 되고, 목록 조회 때 Product 조인도 필요 없어진다.
 */
@Getter
@Entity
@Table(name = "order_item", indexes = @Index(name = "idx_order_item_order_id", columnList = "order_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false, length = 100)
    private String productName;

    @Column(nullable = false)
    private long unitPrice;

    @Column(nullable = false)
    private int quantity;

    OrderItem(Order order, Long productId, String productName, long unitPrice, int quantity) {
        this.order = order;
        this.productId = productId;
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
    }
}
