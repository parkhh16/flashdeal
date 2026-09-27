# 재고는 언제 빼야 하나: 주문 시점 선점 + TTL, 그리고 사라지는 시연 특가

## 1. 차감 시점: 결제 완료 시점 vs 주문 시점

| 방식 | 문제 |
|---|---|
| 결제 완료 시점에 차감 | 결제까지 마친 사람에게 "품절입니다, 환불해 드릴게요"라고 해야 함. 선착순 특가에서는 최악의 경험 |
| **주문 시점에 차감 (채택)** | 결제하지 않고 떠나면 재고가 묶임 → **10분 TTL + 만료 스케줄러로 복구** |

주문을 만들 때 재고를 먼저 확보(선점)하고, 10분 안에 결제하지 않으면 `OrderExpiryScheduler`가 5초마다 만료된 주문을 찾아 재고를 돌려줍니다.

- **만료와 결제 시작이 동시에 일어나면?** 같은 주문을 두 작업이 동시에 바꾸려 하면 `Order`의 `@Version`(낙관적 락)으로 먼저 커밋한 쪽만 이깁니다. 충돌이 드문 곳이라 낙관적 락이 맞는 자리입니다.
- **한 건이 실패하면?** 만료는 주문마다 별도 트랜잭션이라, 한 건이 실패해도 나머지는 계속 처리됩니다.
- **결제 중(PAYING)인 주문은** 만료 대상에서 뺍니다. 결제 결과를 모르는 상태에서 재고를 풀면 안 되기 때문입니다 ([결제 장애 대응](06-payment-failure.md)).

## 2. 며칠 지나면 "오늘의 특가"가 비어 버리는 문제

### 문제

시드 특가는 "서버를 띄운 시각 기준 상대 시간"(지금 - 1시간 ~ 지금 + 6시간 등)으로 만듭니다. 재시작마다 데이터가 초기화되는 H2에서는 항상 특가가 살아 있었는데, **MySQL처럼 데이터가 남는 환경에서는 하루만 지나도 특가가 전부 끝나서** "오늘의 특가"가 비고 해당 상품은 구매도 막혔습니다.

### 해결: 끝난 시드 특가를 같은 시간표로 다시 편성

`DemoDealRotator`가 5분마다 끝난 시드 특가를 같은 시간표(`DemoDeals`)로 다시 엽니다. 시연용 기능이라 설정 하나(`flashdeal.demo.rotate-deals=false`)로 끌 수 있습니다.

시연용이어도 재고를 건드리는 코드라서, [재고가 16,602개로 부풀었던 문제](04-stock-lost-update.md)를 다시 만들지 않도록 조건을 걸었습니다.

- **끝난 지 15분이 지난 특가만** 다시 엽니다. 결제 대기 TTL(10분)보다 길게 잡아서, 종료 직전에 들어온 주문의 처리가 끝날 시간을 줍니다.
- **결제 대기 주문이 재고를 쥐고 있으면 건너뜁니다.** 재고를 덮어쓴 뒤에 그 주문들이 만료되면 재고가 부풀기 때문입니다.
- **조건부 UPDATE**(`... WHERE deal_end_at < ?`)라서 여러 번 실행돼도 한 번만 반영됩니다.
- 시간표에 없는 상품(관리자가 직접 만든 특가)은 건드리지 않습니다.

실제 서비스라면 특가를 상품의 속성이 아니라 **회차(deal round) 엔티티**로 분리해서, 회차별 한정 수량과 판매량을 따로 집계하는 것이 맞다고 봅니다.

## 관련 코드

- [`OrderExpiryScheduler`](../../backend/src/main/java/com/flashdeal/order/OrderExpiryScheduler.java): 미결제 주문 만료 + 재고 복구
- [`Order`](../../backend/src/main/java/com/flashdeal/order/Order.java), [`OrderStatus`](../../backend/src/main/java/com/flashdeal/order/OrderStatus.java): `@Version`, 상태 머신
- [`DemoDealRotator`](../../backend/src/main/java/com/flashdeal/common/config/DemoDealRotator.java), [`DemoDeals`](../../backend/src/main/java/com/flashdeal/common/config/DemoDeals.java): 시연 특가 재편성
- 테스트: `OrderExpiryTest`, `OrderStatusTest`, `DemoDealRotatorTest`
