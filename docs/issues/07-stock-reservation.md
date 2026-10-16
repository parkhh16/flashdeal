# 재고는 언제 빼야 하나: 주문 시점 선점 + TTL

## 1. 차감 시점: 결제 완료 시점 vs 주문 시점

| 방식 | 문제 |
|---|---|
| 결제 완료 시점에 차감 | 결제까지 마친 사람에게 "품절입니다, 환불해 드릴게요"라고 해야 함. 재고가 적은 인기 상품에서는 최악의 경험 |
| **주문 시점에 차감 (채택)** | 결제하지 않고 떠나면 재고가 묶임 → **10분 TTL + 만료 스케줄러로 복구** |

주문을 만들 때 재고를 먼저 확보(선점)하고, 10분 안에 결제하지 않으면 `OrderExpiryScheduler`가 5초마다 만료된 주문을 찾아 재고를 돌려줍니다.

- **만료와 결제 시작이 동시에 일어나면?** 같은 주문을 두 작업이 동시에 바꾸려 하면 `Order`의 `@Version`(낙관적 락)으로 먼저 커밋한 쪽만 이깁니다. 충돌이 드문 곳이라 낙관적 락이 맞는 자리입니다.
- **한 건이 실패하면?** 만료는 주문마다 별도 트랜잭션이라, 한 건이 실패해도 나머지는 계속 처리됩니다.
- **결제 중(PAYING)인 주문은** 만료 대상에서 뺍니다. 결제 결과를 모르는 상태에서 재고를 풀면 안 되기 때문입니다 ([결제 장애 대응](06-payment-failure.md)).

## 2. (기록) 시간 한정 특가를 없애면서 같이 지운 장치

처음에는 상품마다 판매 기간(오픈·종료 시각)과 특가 한정 수량이 있었습니다. 시드 특가를 "서버를 띄운 시각 기준 상대 시간"으로 만들었더니, MySQL처럼 데이터가 남는 환경에서는 하루만 지나도 특가가 전부 끝나서 화면이 비었습니다. 그래서 끝난 시드 특가를 5분마다 같은 시간표로 다시 여는 작업(`DemoDealRotator`)을 두었습니다.

시연용이어도 재고를 건드리는 코드라서 [재고가 16,602개로 부풀었던 문제](04-stock-lost-update.md)를 다시 만들지 않도록 조건을 걸었습니다.

- 끝난 지 15분(결제 대기 TTL 10분보다 길게)이 지난 특가만 다시 열기
- 결제 대기 주문이 재고를 쥐고 있으면 건너뛰기
- `... WHERE deal_end_at < ?` 조건부 UPDATE로 여러 번 실행돼도 한 번만 반영

이후 프로젝트를 시간 한정 이벤트 없는 일반 마켓으로 정리하면서 판매 기간과 한정 수량 컬럼을 지웠고(`V5__remove_time_limited_deals.sql`), 이 작업도 함께 없앴습니다. 남은 교훈은 하나입니다. **시연 데이터를 유지하려는 코드도 재고를 건드리는 순간 운영 코드와 같은 안전 조건이 필요합니다.**

1인 구매 제한은 시간 이벤트가 아니라 상품별 구매 수량 제한이라 그대로 남겼습니다 ([1인 구매 제한](02-purchase-limit.md)).

## 관련 코드

- [`OrderExpiryScheduler`](../../backend/src/main/java/com/flashdeal/order/OrderExpiryScheduler.java): 미결제 주문 만료 + 재고 복구
- [`Order`](../../backend/src/main/java/com/flashdeal/order/Order.java), [`OrderStatus`](../../backend/src/main/java/com/flashdeal/order/OrderStatus.java): `@Version`, 상태 머신
- [`V5__remove_time_limited_deals.sql`](../../backend/src/main/resources/db/migration/V5__remove_time_limited_deals.sql): 판매 기간·한정 수량 컬럼 제거
- 테스트: `OrderExpiryTest`, `OrderStatusTest`
