package com.flashdeal.payment;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    long countByOrderId(Long orderId);

    List<Payment> findByOrderIdOrderByIdDesc(Long orderId);

    List<Payment> findByOrderIdIn(java.util.Collection<Long> orderIds);

    @Query("select p from Payment p where p.status in :statuses and p.createdAt < :before order by p.id")
    List<Payment> findPending(@Param("statuses") Collection<PaymentStatus> statuses,
                              @Param("before") LocalDateTime before, Pageable pageable);

    /** 정합성 점검: PAID 주문인데 승인된 결제가 없거나, 승인 금액 합계가 주문 금액과 다른 주문 */
    @Query(value = """
            select o.id as orderId, o.order_no as orderNo, o.status as orderStatus, o.total_amount as orderAmount,
                   coalesce(sum(case when p.status = 'APPROVED' then p.amount end), 0) as approvedAmount
            from orders o
            left join payment p on p.order_id = o.id
            where o.status = 'PAID' or p.status = 'APPROVED'
            group by o.id, o.order_no, o.status, o.total_amount
            having o.status <> 'PAID'
                or coalesce(sum(case when p.status = 'APPROVED' then p.amount end), 0) <> o.total_amount
            """, nativeQuery = true)
    List<MismatchRow> findMismatches();

    interface MismatchRow {
        Long getOrderId();

        String getOrderNo();

        String getOrderStatus();

        Long getOrderAmount();

        Long getApprovedAmount();
    }
}
