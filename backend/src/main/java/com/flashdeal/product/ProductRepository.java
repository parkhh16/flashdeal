package com.flashdeal.product;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    /** 사이드바 카테고리 트리의 상품 개수 */
    @Query("select p.subCategory as subCategory, count(p) as count from Product p where p.active = true group by p.subCategory")
    List<SubCategoryCount> countBySubCategory();

    interface SubCategoryCount {
        SubCategory getSubCategory();

        long getCount();
    }

    @Query("select new com.flashdeal.product.PurchaseRule(p.id, p.name, p.active, p.dealStartAt, p.dealEndAt, p.perUserLimit) " +
            "from Product p where p.id in :ids")
    List<PurchaseRule> findPurchaseRules(@Param("ids") java.util.Collection<Long> ids);

    @Query("select count(p) from Product p where p.active = true and p.dealEndAt > :now")
    long countActiveDeals(@Param("now") java.time.LocalDateTime now);

    /** PESSIMISTIC: SELECT ... FOR UPDATE */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "10000"))
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);

    /** OPTIMISTIC: 읽은 시점의 version과 같을 때만 차감한다. 0이 반환되면 충돌 */
    @Modifying
    @Query("update Product p set p.stock = p.stock - :qty, p.soldCount = p.soldCount + :qty, p.version = p.version + 1 " +
            "where p.id = :id and p.version = :version and p.stock >= :qty")
    int decreaseIfVersionMatches(@Param("id") Long id, @Param("qty") int qty, @Param("version") long version);

    /** ATOMIC_UPDATE: 확인과 차감을 쿼리 하나로 처리한다. 0이 반환되면 재고 부족 */
    @Modifying
    @Query("update Product p set p.stock = p.stock - :qty, p.soldCount = p.soldCount + :qty, p.version = p.version + 1 " +
            "where p.id = :id and p.stock >= :qty")
    int decreaseIfEnough(@Param("id") Long id, @Param("qty") int qty);

    Optional<Product> findFirstByNameOrderByIdAsc(String name);

    /**
     * 끝난 특가를 새 회차로 재편성한다. "종료된 지 일정 시간이 지난 경우에만" 조건을 WHERE에 넣어,
     * 여러 번 호출되거나 동시에 호출돼도 한 번만 반영되게 한다 (조건부 UPDATE).
     */
    @Modifying
    @Query("update Product p set p.dealStartAt = :start, p.dealEndAt = :end, p.stock = :stock, p.version = p.version + 1 " +
            "where p.id = :id and p.dealEndAt < :endedBefore")
    int rescheduleEndedDeal(@Param("id") Long id, @Param("start") java.time.LocalDateTime start,
                            @Param("end") java.time.LocalDateTime end, @Param("stock") int stock,
                            @Param("endedBefore") java.time.LocalDateTime endedBefore);

    /** 관리자 재고 조정: 덮어쓰지 않고 증감한다. 차감 결과가 음수가 되면 0건 */
    @Modifying
    @Query("update Product p set p.stock = p.stock + :delta, p.version = p.version + 1 where p.id = :id and p.stock + :delta >= 0")
    int adjustStock(@Param("id") Long id, @Param("delta") int delta);

    @Query("select p.stock from Product p where p.id = :id")
    int findStockById(@Param("id") Long id);

    @Modifying
    @Query("update Product p set p.stock = p.stock + :qty, p.soldCount = p.soldCount - :qty, p.version = p.version + 1 where p.id = :id")
    int increaseStock(@Param("id") Long id, @Param("qty") int qty);

    @Query("select p from Product p " +
            "where p.active = true " +
            "and (:category is null or p.category = :category) " +
            "and (:minPrice is null or p.price >= :minPrice) " +
            "and (:maxPrice is null or p.price <= :maxPrice) " +
            "and (:keyword is null or lower(p.name) like lower(concat('%', :keyword, '%')) " +
            "     or lower(p.description) like lower(concat('%', :keyword, '%'))) " +
            "order by p.price asc")
    List<Product> search(@Param("category") ProductCategory category,
                         @Param("minPrice") Long minPrice,
                         @Param("maxPrice") Long maxPrice,
                         @Param("keyword") String keyword);
}
