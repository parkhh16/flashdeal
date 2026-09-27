package com.flashdeal.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Order> {

    /**
     * items는 지연 로딩이다. 주문 N건의 items에 접근하면 N번 추가 쿼리가 나간다 (N+1).
     * default_batch_fetch_size 설정으로 IN 쿼리 한 번에 묶어서 해결한다 (OrderQueryPerformanceTest 참고).
     * 컬렉션 fetch join은 페이징과 함께 쓰면 전체를 메모리로 올린 뒤 잘라서 쓰지 않았다.
     */
    Page<Order> findByUserId(Long userId, Pageable pageable);

    /** 본인 주문만 조회. 남의 주문이면 403 대신 404를 반환해서 주문 존재 여부를 노출하지 않는다 */
    Optional<Order> findByIdAndUserId(Long id, Long userId);

    long countByUserIdAndStatusIn(Long userId, java.util.Collection<OrderStatus> statuses);

    @Query("select o.userId as userId, count(o) as count from Order o where o.userId in :userIds group by o.userId")
    List<UserOrderCount> countByUserIds(@Param("userIds") java.util.Collection<Long> userIds);

    interface UserOrderCount {
        Long getUserId();

        long getCount();
    }

    /** 재고를 점유 중인(결제 대기, 결제 중, 결제 완료) 주문에서 이 상품을 몇 개 샀는지 */
    @Query("select coalesce(sum(i.quantity), 0) from OrderItem i join i.order o " +
            "where o.userId = :userId and i.productId = :productId and o.status in :statuses")
    long sumQuantity(@Param("userId") Long userId, @Param("productId") Long productId,
                     @Param("statuses") java.util.Collection<OrderStatus> statuses);

    /** 상품별로 아직 결제되지 않은 주문(결제 대기·결제 확인 중)이 쥐고 있는 수량 */
    @Query("select coalesce(sum(i.quantity), 0) from OrderItem i join i.order o " +
            "where i.productId = :productId and o.status in :statuses")
    long sumHeldQuantity(@Param("productId") Long productId, @Param("statuses") java.util.Collection<OrderStatus> statuses);

    @Query("select i.productId as productId, sum(i.quantity) as quantity from OrderItem i join i.order o " +
            "where o.status in :statuses group by i.productId")
    List<ProductHeld> sumHeldQuantityByProduct(@Param("statuses") java.util.Collection<OrderStatus> statuses);

    interface ProductHeld {
        Long getProductId();

        long getQuantity();
    }

    @Query("select o.id from Order o where o.status = :status and o.expiresAt < :now order by o.id")
    List<Long> findIdsToExpire(@Param("status") OrderStatus status, @Param("now") LocalDateTime now, Pageable pageable);
}
