# 따닥 클릭과 재전송으로 주문이 두 번 생기는 문제

## 문제

사용자가 주문 버튼을 빠르게 두 번 누르거나, 모바일 네트워크가 끊겨서 클라이언트가 같은 요청을 다시 보내면 주문과 결제가 두 번 생깁니다. 재고가 한정된 상품에서는 다른 사람이 살 수 있던 재고까지 한 사람이 가져가게 됩니다.

## 해결: `Idempotency-Key` + DB 유니크 제약

주문·결제 API는 요청마다 `Idempotency-Key` 헤더를 받습니다. 클라이언트는 "버튼 한 번 누름 = 키 하나"로 만들고, 재전송할 때는 같은 키를 씁니다.

```
1. INSERT idempotency_record(user_id, key, request_hash, IN_PROGRESS)   ← UNIQUE(user_id, key)
   └ 이미 있으면
       - 요청 내용(해시)이 다름 → 422 (같은 키로 다른 요청을 보낸 클라이언트 버그)
       - 아직 처리 중         → 409
       - 처리 완료            → 저장해 둔 응답을 그대로 돌려줌 (Idempotent-Replayed: true)
2. INSERT에 성공한 요청만 실제로 주문 처리 → COMPLETED + 응답 본문 저장
3. 처리 중 실패하면 레코드를 지움 → 같은 키로 다시 시도할 수 있게
```

### 왜 DB 유니크 제약인가

`synchronized`나 애플리케이션 메모리의 락은 서버가 두 대만 돼도 소용이 없습니다. 유니크 제약은 서버 대수와 상관없이 DB가 보장합니다. "먼저 조회해서 없으면 저장"하는 방식은 조회와 저장 사이에 다른 요청이 끼어들 수 있어서, 동시 요청 중 하나만 통과시키는 역할은 INSERT와 유니크 제약에 맡겼습니다.

### 왜 요청 해시를 저장하나

같은 키로 수량 1개 주문을 보냈다가 수량 2개로 다시 보내는 경우, 조용히 첫 응답을 돌려주면 클라이언트는 2개를 샀다고 착각합니다. 요청 본문 해시를 저장해 두고 다르면 422로 알립니다.

## 결과

- 같은 키로 요청 20개를 동시에 보내도 **주문 1건, 재고 1개 차감**
- 두 번째부터는 첫 응답이 그대로 재생되고, 활동 로그에 "멱등성 재생"으로 따로 남습니다.
- `OrderApiTest.doubleClick`이 같은 시나리오를 자동으로 검증합니다 (`./gradlew test --tests "*OrderApiTest"`).

결제는 한 번 더 막힙니다. 결제 시작 시 주문 상태가 `PENDING_PAYMENT → PAYING`으로 바뀌는데, 상태 머신이 `PAYING → PAYING` 전이를 허용하지 않아서 같은 주문을 동시에 두 번 결제할 수 없습니다.

## 남은 한계

`IN_PROGRESS` 상태로 서버가 죽으면 그 키는 계속 409를 받습니다. 일정 시간이 지난 `IN_PROGRESS` 레코드를 정리하는 작업이 필요합니다.

## 관련 코드

- [`IdempotencyService`](../../backend/src/main/java/com/flashdeal/idempotency/IdempotencyService.java)
- [`IdempotencyRecord`](../../backend/src/main/java/com/flashdeal/idempotency/IdempotencyRecord.java): `UNIQUE(user_id, idempotency_key)`
- 테스트: `OrderApiTest` (`doubleClick`, `replay`, `keyReusedWithDifferentBody`)
