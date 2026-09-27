# 로그 저장 때문에 주문이 느려지면 안 된다: 활동 로그 비동기 배치 적재

## 배경

"이 사용자가 방금 무엇을 했는지"(로그인, 검색, 상품 조회, 주문, 결제)를 관리자가 추적할 수 있어야 했습니다. 고객 문의가 오면 요청 하나하나를 따라가 볼 수 있어야 하기 때문입니다.

## 문제

요청마다 로그를 동기로 INSERT하면

- 주문 API의 응답 시간이 로그 저장 시간만큼 늘어나고,
- 부하가 몰릴 때 DB 커넥션을 두 배로 쓰고,
- DB에 문제가 생기면 **로그를 못 써서 주문까지 실패**할 수 있습니다.

## 해결: 메모리 큐 + 300ms 배치 INSERT

```
요청 스레드 ── offer ──▶ 메모리 큐(2만 건) ── 300ms마다 ──▶ JDBC batch INSERT (최대 1,000건씩)
```

- 요청 스레드는 큐에 넣기만 하고 바로 돌아갑니다 (논블로킹).
- 백그라운드 스레드가 300ms마다 모아서 한 번에 저장합니다.
- **큐가 가득 차면(DB 장애 등) 로그를 버리고 버린 개수만 셉니다.** 버린 개수는 관리자 대시보드에 표시됩니다. 로그 때문에 주문이 실패하는 것보다 로그 일부를 잃는 쪽이 낫다고 판단했습니다.
- 저장은 항상 **독립된 새 트랜잭션(REQUIRES_NEW)** 에서 합니다. 이 결정의 배경은 [readOnly 트랜잭션 문제](03-readonly-transaction.md)에 있습니다.

서버가 죽으면 큐에 남은 최대 300ms 분량을 잃을 수 있습니다. 감사(audit) 로그가 아니라 운영 분석용이라 허용했고, 절대 잃으면 안 되는 기록이라면 다른 설계(동기 저장이나 메시지 큐)가 필요합니다.

## 무엇을 남기고 무엇을 남기지 않나

- **의미 있는 요청만** 남깁니다: 로그인, 검색, 상품 조회, 주문, 멱등성 재생, 결제, 관리자 작업, 서버 에러(5xx).
- 목록 새로고침, 폴링, 로그 조회 API 자신은 남기지 않습니다. 다 남기면 정작 필요한 기록이 묻힙니다.
- **개인정보는 마스킹**합니다. 로그인 이메일은 `us***@flashdeal.com`처럼 저장합니다.

## 요청 하나를 끝까지 따라가기: traceId

모든 요청에 traceId를 붙여 MDC에 넣고, 서버 로그·에러 응답·`X-Trace-Id` 응답 헤더·활동 로그에 같은 값을 남깁니다. 사용자가 에러 화면의 traceId를 알려주면, 그 요청의 서버 로그와 활동 기록을 한 번에 찾을 수 있습니다.

## 사용자에게는 사용자의 언어로

같은 데이터를 보여줘도 관리자와 사용자에게 필요한 것이 다릅니다.

| 화면 | 보여주는 것 |
|---|---|
| 관리자 활동 로그 | 사용자·기간·이벤트·결과 필터, 사용자별 타임라인, 상태 코드, 응답 시간, traceId |
| 내 활동 (사용자) | 최근 검색어, 최근 본 상품. 상태 코드·응답 시간 같은 개발자 정보는 응답에 넣지 않음 |

## 관련 코드

- [`ActivityLogFilter`](../../backend/src/main/java/com/flashdeal/activity/ActivityLogFilter.java): 기록할 요청 선별
- [`AuthService.mask`](../../backend/src/main/java/com/flashdeal/auth/AuthService.java): 로그인 아이디·이메일 마스킹
- [`ActivityRecorder`](../../backend/src/main/java/com/flashdeal/activity/ActivityRecorder.java): 메모리 큐, 300ms 배치 INSERT, 버린 개수 집계
- [`ActivityQueryService`](../../backend/src/main/java/com/flashdeal/activity/ActivityQueryService.java): 관리자 조회, 대시보드 요약
- [`TraceIdFilter`](../../backend/src/main/java/com/flashdeal/common/logging/TraceIdFilter.java)
- 테스트: `ActivityLogTest`
