# PG는 승인했는데 우리 DB는 모를 때: 외부 결제 연동과 장애 대응

## 문제

결제는 우리 DB와 외부 PG, 두 시스템에 걸친 작업이라 한 트랜잭션으로 묶을 수 없습니다. 그래서 이런 상황이 생깁니다.

- PG가 느려지면, 그동안 우리 서버의 DB 커넥션도 같이 묶입니다.
- PG에 승인 요청을 보냈는데 응답이 오지 않으면, 승인됐는지 알 수 없습니다.
- PG는 승인했는데 결과를 저장하기 직전에 우리 서버가 죽으면, **돈은 빠졌는데 주문은 "결제 중"** 으로 남습니다.

## 설계 원칙

```
[TX1] 주문 PAYING + 결제 REQUESTED 기록  →  PG HTTP 호출 (트랜잭션 밖)  →  [TX2] 결과 반영
```

1. **PG 호출은 트랜잭션 밖에서 합니다.** 트랜잭션 안에서 호출하면 PG가 3초 늦어질 때 DB 커넥션도 3초씩 묶입니다. 커넥션 풀이 30개이므로 동시 결제 30건이면 풀이 바닥나고, 결제와 상관없는 상품 조회까지 멈춥니다. 트랜잭션 밖에서 지연 로딩이 일어나 커넥션을 다시 잡는 일이 없도록 OSIV도 껐습니다.
2. **호출하기 전에 먼저 기록합니다(REQUESTED).** TX2 전에 서버가 죽어도 "이 결제를 시도했다"는 흔적이 DB에 남아서 나중에 복구할 수 있습니다.
3. **타임아웃은 실패가 아니라 UNKNOWN입니다.** 응답을 못 받았을 뿐 PG는 승인했을 수 있습니다. 실패로 처리하고 재고를 풀면 돈만 빠진 주문이 생깁니다.
4. **재시도는 PG 멱등 키(`paymentKey`)가 있어서 안전합니다.** 같은 키로 다시 요청하면 PG는 새로 승인하지 않고 기존 승인 결과를 돌려줍니다. 재시도는 최대 3회, 200ms → 400ms로 간격을 늘립니다.

## 대사(Reconcile) 배치

UNKNOWN이나 REQUESTED로 남은 결제는 `PaymentReconciler`가 10초마다 PG 조회 API로 확인해서 확정합니다.

- 방금 생긴 결제를 건드리지 않도록 5초(grace)가 지난 것만 대상으로 합니다.
- PG에서 승인됨 → 결제 APPROVED, 주문 PAID
- PG에 기록 없음 → 결제 FAILED, 주문은 결제 대기로 돌아가 재결제 가능 (결제 기한이 지났으면 만료 + 재고 복구)
- PG도 아직 모름 → 그대로 두고 다음 대사에서 다시 확인
- 요청 흐름과 대사가 같은 결제를 동시에 확정하려 해도, 이미 확정된 결제는 다시 바꾸지 않습니다.
- **PAYING 상태는 만료 스케줄러도, 관리자 취소도 건드리지 않고 대사로만 빠져나갑니다.** 결과를 모르는 상태에서 재고를 풀면 "돈은 빠졌는데 상품은 다른 사람에게 팔린" 상황이 되기 때문입니다.

## 장애를 직접 주입해서 확인

Mock PG를 앱 안에 만들고, 관리자 화면에서 장애를 켜고 끌 수 있게 했습니다 (지연, 5xx 비율, 카드 거절 비율, 응답 타임아웃, 승인 후 우리 DB 장애).

| 주입한 장애 | 동작 | 최종 상태 |
|---|---|---|
| 카드 거절 | 402, 주문을 PENDING_PAYMENT로 되돌림 (재고는 계속 선점) | 재결제하면 PAID |
| PG가 승인 후 3초 지연 | 타임아웃(2초) → 같은 paymentKey로 재시도 → PG가 기존 승인 반환 | **PAID, 결제 1건 (이중 결제 없음)** |
| PG 5xx 지속 | 재시도 3회 후 202 UNKNOWN, 주문은 PAYING 유지 | 대사: PG에 기록 없음 → FAILED → 재결제 가능 |
| **PG 승인 직후 우리 DB 장애** | 500, 결제는 REQUESTED로 남음. 재결제는 상태 머신이 막음 | **대사: PG 조회 결과 APPROVED → PAID로 복구** |

## 리팩토링하다 찾은 버그: 거절된 결제를 승인으로 오판할 뻔함

PG 조회 API는 거절·취소된 결제도 **HTTP 200**으로 응답하는데, 클라이언트가 "200이면 승인"으로 해석하고 있었습니다. 거절 응답을 받은 직후 서버가 죽으면, 대사 배치가 그 결제를 **승인으로 확정**할 수 있었습니다.

→ HTTP 상태 코드가 아니라 **응답 본문의 결제 상태**로 판단하도록 고쳤습니다. 모르는 상태 값이 오면 UNKNOWN으로 두고 다음 대사에서 다시 확인합니다.

## 관리자 환불 취소: 순서가 중요하다

```
PG 환불 (트랜잭션 밖) → 성공한 경우에만 [결제 취소 + 주문 취소 + 재고 복구]를 한 트랜잭션으로
```

순서를 반대로 하면, 우리 쪽은 취소했는데 PG 환불이 실패해서 "우리는 취소, PG는 결제 유지"라는 불일치가 생깁니다. 환불이 실패하면 아무것도 바꾸지 않습니다. 결과를 모르는 PAYING 주문은 취소할 수 없습니다.

## 정합성 리포트

다음 두 가지를 SQL 한 번으로 찾아 관리자 화면에 보여줍니다.

- PAID인데 승인된 결제 금액 합계가 주문 금액과 다른 주문
- 승인된 결제가 있는데 PAID가 아닌 주문

장애 주입 → 대사 실행 → 정합성 점검 0건까지 관리자 화면에서 직접 확인할 수 있습니다.

## 남은 한계

결제 결과 반영은 동기 방식입니다. 트래픽이 커지면 Outbox 패턴과 메시지 큐로 결과 반영을 분리하는 것이 다음 단계입니다.

## 관련 코드

- [`PaymentService`](../../backend/src/main/java/com/flashdeal/payment/PaymentService.java), [`PaymentTxService`](../../backend/src/main/java/com/flashdeal/payment/PaymentTxService.java): TX1 → PG 호출 → TX2
- [`HttpPgClient`](../../backend/src/main/java/com/flashdeal/payment/HttpPgClient.java): 타임아웃, 재시도, 응답 본문 기준 판정
- [`PaymentReconciler`](../../backend/src/main/java/com/flashdeal/payment/PaymentReconciler.java): 대사 배치
- [`OrderCancelService`](../../backend/src/main/java/com/flashdeal/order/OrderCancelService.java): 환불 취소
- [`MockPgController`](../../backend/src/main/java/com/flashdeal/mockpg/MockPgController.java), [`ChaosSettings`](../../backend/src/main/java/com/flashdeal/mockpg/ChaosSettings.java): Mock PG와 장애 주입
- 테스트: `PaymentFailureScenarioTest`, `AdminFeaturesTest` (`cancelPaidRefunds`, `cannotCancelPaying`)
