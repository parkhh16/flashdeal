# 주문 목록 조회: N+1은 22 → 3 쿼리, 인덱스로 읽는 행은 994 → 10

## 1. N+1: 22 쿼리 → 3 쿼리

### 문제

내 주문 목록(20건)을 조회하면 쿼리가 22번 나갔습니다.

```
orders 조회 1 + count 1 + 주문마다 order_item 조회 20 = 22 쿼리
```

### 해결: fetch join 대신 `default_batch_fetch_size`

컬렉션(`order_item`)을 fetch join하면서 페이징을 걸면, Hibernate가 **전체 결과를 메모리에 올린 뒤 잘라서** 돌려줍니다. 주문이 많아지면 메모리가 위험해집니다.

그래서 지연 로딩은 그대로 두고, 여러 주문의 품목을 **IN 쿼리 한 번으로 묶어 읽는** `default_batch_fetch_size: 100`을 설정했습니다.

```
orders 조회 1 + count 1 + order_item IN (...) 1 = 3 쿼리
```

관리자 주문 목록과 활동 로그도 사용자 이름과 결제 상태를 IN 쿼리 한 번으로 가져오게 해서, **페이지 크기와 상관없이 쿼리 수가 고정**됩니다.

테스트는 Hibernate 통계로 실제 실행된 쿼리 수를 셉니다 (`OrderQueryPerformanceTest`).

## 2. 내 주문 목록 인덱스: 994행 → 10행

### 문제

내 주문 목록 쿼리는 `WHERE user_id = ? ORDER BY id DESC LIMIT 10`입니다. 주문 5,000건(사용자 100명)을 넣고 MySQL 8에서 `EXPLAIN ANALYZE`로 확인했습니다.

```
[인덱스 없음]
-> Limit: 10 row(s)  (actual time=0.112..0.727 rows=10)
    -> Filter: (orders.user_id = 7)
        -> Index scan on orders using PRIMARY (reverse)  (actual rows=994)   ← 남의 주문까지 994행을 읽고 10개를 고름
```

PK를 역순으로 훑으면서 다른 사용자의 주문까지 읽고 버립니다. 읽는 양은 전체 주문 수에 비례해서 늘어납니다.

### 해결: 복합 인덱스 `(user_id, id)`

```
[인덱스 (user_id, id)]
-> Limit: 10 row(s)  (actual time=0.134..0.138 rows=10)
    -> Index lookup on orders using idx_orders_user_id_id (user_id=7) (reverse)  (actual rows=10)   ← 정확히 10행만
```

- 등호 조건 컬럼(`user_id`)을 앞에, 정렬 컬럼(`id`)을 뒤에 두면 **필터와 정렬을 인덱스 하나로** 처리해서 별도 정렬(filesort)이 없습니다.
- 주문이 100만 건이 되어도 읽는 행은 10행입니다.

행이 적으면 옵티마이저가 인덱스가 있어도 풀 스캔을 고를 수 있어서, 테스트에서는 5,000건을 넣고 `ANALYZE TABLE`로 통계를 갱신한 뒤 비교합니다. 실행 계획 문법이 DB마다 달라서 H2와 MySQL을 구분해 확인합니다.

## 관련 코드

- [`application.yml`](../../backend/src/main/resources/application.yml): `default_batch_fetch_size`, `open-in-view: false`
- [`V1__init.sql`](../../backend/src/main/resources/db/migration/V1__init.sql): `idx_orders_user_id_id`
- [`OrderRepository`](../../backend/src/main/java/com/flashdeal/order/OrderRepository.java)
- 테스트: `OrderQueryPerformanceTest` (`nPlusOne`, `batchFetch`, `explain`)
