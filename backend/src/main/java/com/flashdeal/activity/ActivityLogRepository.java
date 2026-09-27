package com.flashdeal.activity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long>, JpaSpecificationExecutor<ActivityLog> {

    /** 내 활동: 최근 성공한 이벤트 (인덱스 user_id, id 역순 스캔) */
    @Query("select a from ActivityLog a where a.userId = :userId and a.eventType = :type and a.statusCode < 400 order by a.id desc")
    List<ActivityLog> findRecent(@Param("userId") Long userId, @Param("type") ActivityEventType type, Pageable pageable);
}
