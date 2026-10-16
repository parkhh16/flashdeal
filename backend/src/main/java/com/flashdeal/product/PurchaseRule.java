package com.flashdeal.product;

/**
 * 주문 전 검증에 필요한 값만 담은 DTO 프로젝션.
 * 엔티티로 미리 읽으면 영속성 컨텍스트에 캐시되어, 뒤이은 SELECT FOR UPDATE가 최신 재고 대신 캐시된 값을 돌려준다
 * (Hibernate는 이미 관리 중인 엔티티를 쿼리 결과로 덮어쓰지 않음). 그래서 규칙 검증은 엔티티가 아닌 프로젝션으로 읽는다.
 */
public record PurchaseRule(Long id, String name, boolean active, Integer perUserLimit) {

    public boolean limited() {
        return perUserLimit != null;
    }
}
