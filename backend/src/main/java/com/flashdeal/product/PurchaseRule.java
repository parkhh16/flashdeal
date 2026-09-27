package com.flashdeal.product;

import com.flashdeal.common.error.BusinessException;
import com.flashdeal.common.error.ErrorCode;

import java.time.LocalDateTime;

/**
 * 주문 전 검증에 필요한 값만 담은 DTO 프로젝션.
 * 엔티티로 미리 읽으면 영속성 컨텍스트에 캐시되어, 뒤이은 SELECT FOR UPDATE가 최신 재고 대신 캐시된 값을 쓰게 된다
 * (Hibernate는 이미 관리 중인 엔티티를 쿼리 결과로 덮어쓰지 않음). 그래서 규칙 검증은 엔티티가 아닌 프로젝션으로 한다.
 */
public record PurchaseRule(Long id, String name, boolean active, LocalDateTime dealStartAt, LocalDateTime dealEndAt,
                           Integer perUserLimit) {

    public void checkSalePeriod(LocalDateTime now) {
        if (dealStartAt != null && now.isBefore(dealStartAt)) {
            throw new BusinessException(ErrorCode.DEAL_NOT_OPEN, "'%s' 특가는 아직 오픈 전입니다.".formatted(name));
        }
        if (dealEndAt != null && now.isAfter(dealEndAt)) {
            throw new BusinessException(ErrorCode.DEAL_ENDED, "'%s' 특가 판매가 종료되었습니다.".formatted(name));
        }
    }

    public boolean limited() {
        return perUserLimit != null;
    }
}
