# 서버를 두 대로 늘리면 깨지는 것들: 스케줄러 중복, 캐시 무효화, 요청 한도

## 문제

지금까지는 서버 한 대를 기준으로 만들었습니다. 서버를 두 대로 늘려 프록시 뒤에 두기 전에, 그대로 동작하는 것과 깨지는 것을 나눠 봤습니다.

**그대로 동작하는 것**은 정합성을 DB에 맡긴 부분입니다. 재고는 원자적 UPDATE, 1인 구매 제한은 사용자 행 락, 멱등성은 유니크 제약, 주문 상태는 `@Version`으로 지킵니다. 요청이 어느 서버로 가든 같은 DB에서 판정됩니다.

**깨지는 것**은 서버 메모리나 서버 안에서 도는 작업에 기댄 부분입니다.

| | 서버 한 대 | 서버 두 대 |
|---|---|---|
| 스케줄러 (주문 만료 5초, 결제 대사 10초) | 한 번 실행 | 두 서버가 같은 주문을 동시에 처리. `@Version` 덕분에 재고가 두 번 복구되지는 않지만 낙관적 락 충돌만 쌓이고, 대사는 같은 결제를 PG에 서버 수만큼 조회 |
| 사용자 상태 캐시 (정지·탈퇴·강제 로그아웃 즉시 반영, [#13](09-auth.md)) | 상태를 바꾼 서버가 캐시를 지움 | 다른 서버는 캐시 TTL(30초) 동안 옛 상태로 인증을 통과시킴 |
| 요청 한도 (이번에 추가) | 서버 메모리로 세면 됨 | 서버마다 세면 한도가 서버 수만큼 늘어남 |
| 첫 기동 시드 (관리자, 상품) | 한 번 | 빈 DB에 두 대가 동시에 뜨면 둘 다 "비어 있음"을 보고 넣으려 함 |
| 활동 로그 큐 ([#14](10-activity-log.md)) | 서버 메모리 큐 → 배치 INSERT | 서버마다 자기 큐 |

캐시를 살펴보다가 **서버 한 대에서도 생기는 버그**를 하나 찾았습니다.

```
관리자 요청 (회원 정지)                       사용자 요청
───────────────────────────────────          ───────────────────────────
트랜잭션 시작
status = SUSPENDED (아직 커밋 전)
캐시 삭제  ◀── 여기서 지웠다
                                              캐시 없음 → DB 조회
                                              커밋 전이라 ACTIVE를 읽음 → 30초 캐시
커밋
                                              → 정지했는데 30초 동안 계속 통과
```

캐시를 트랜잭션 **안에서** 지우고 있어서, 커밋 전 틈에 들어온 요청이 옛 상태를 다시 캐시에 넣을 수 있었습니다.

## 해결

### 1. 스케줄러는 한 서버에서만: ShedLock (DB 테이블 락)

두 스케줄러의 진입점을 [`ClusterLock.runExclusively`](../../backend/src/main/java/com/needsshop/common/cluster/ClusterLock.java)로 감쌌습니다. 락을 얻은 서버만 실행하고, 못 얻은 서버는 기다리지 않고 이번 주기를 건너뜁니다. 주기 작업이라 다음 주기에 다시 시도하면 됩니다.

- **락은 DB에 둡니다** (`shedlock` 테이블, V6). 모든 서버가 이미 같은 DB를 쓰고 있어서 인프라가 늘지 않습니다. 결제를 복구하는 대사 배치가 Redis에 의존하면, Redis가 죽었을 때 돈과 주문이 어긋난 채로 남습니다.
- **만료 판단은 DB 시각으로 합니다** (`usingDbTime()`). 서버마다 시계가 조금씩 달라도 판단 기준이 하나입니다.
- **락을 쥔 서버가 작업 중에 죽어도** `lockAtMostFor`(만료 1분, 대사 5분)가 지나면 다른 서버가 가져갑니다.
- `@SchedulerLock`(AOP) 대신 명시적으로 감쌌습니다. AOP는 테스트가 메서드를 직접 부를 때도 락을 걸고, 락을 못 얻으면 `int` 반환값 자리에 `null`을 돌려줍니다.

락은 정합성 장치가 아닙니다. 정합성은 여전히 `@Version`이 지키고, 락은 같은 일을 두 번 하는 낭비와 PG 중복 조회를 없앱니다.

### 2. 캐시 무효화: 커밋한 뒤에, 모든 서버에서

[`UserAuthCache.evict`](../../backend/src/main/java/com/needsshop/auth/UserAuthCache.java)는 트랜잭션 안에서 불리면 `TransactionSynchronization.afterCommit`에 삭제를 예약합니다. 커밋된 뒤에 지우므로, 그 뒤의 조회는 새 상태만 읽습니다.

다른 서버에는 **Redis Pub/Sub**으로 알립니다. 상태를 바꾼 서버가 채널에 userId를 보내면 모든 서버가 자기 메모리 캐시에서 그 사용자를 지웁니다.

- **캐시 자체는 계속 서버 메모리에 둡니다.** 캐시를 Redis로 옮기면 모든 요청의 인증이 네트워크를 타고, Redis가 죽으면 인증도 같이 죽습니다. 자주 일어나는 조회는 로컬에서 처리하고, 드물게 일어나는 무효화만 전파합니다.
- **전파에 실패해도** 상태 변경은 이미 커밋됐습니다. 메시지를 놓친 서버도 TTL(30초) 안에 따라옵니다. 오래된 상태가 남는 시간에 상한이 있습니다.
- Redis를 끈 환경(로컬, 테스트, 서버 한 대)에서는 알릴 곳이 없으므로 아무것도 하지 않습니다 (`needsshop.redis.enabled`).

### 3. 요청 한도: 공유 카운터, Redis 장애 시에는 허용

[`RateLimitFilter`](../../backend/src/main/java/com/needsshop/common/ratelimit/RateLimitFilter.java)가 남용을 막습니다. 넘으면 `429 Too Many Requests`, `Retry-After` 헤더, 에러 코드 `C006`을 돌려줍니다.

| 대상 | 기준 | 한도 (분당) | 막는 것 |
|---|---|---|---|
| 로그인 | IP | 20 | 계정 잠금(5회)은 계정 단위라, 여러 계정을 돌려 가며 찍는 공격은 못 막음 |
| 회원가입 | IP | 10 | 계정 대량 생성 |
| 주문 | 사용자 | 30 | 매크로로 한정 재고를 쓸어 가기 |

- **모든 서버가 Redis의 같은 카운터를 셉니다.** `INCR`과 만료 설정(`PEXPIRE`)을 Lua 스크립트 하나로 묶어서, 두 명령 사이에 서버가 죽어 만료 없는 키가 남는 일을 막습니다.
- **고정 윈도(1분)입니다.** 윈도 경계에서 순간적으로 한도의 두 배까지 통과할 수 있지만, 남용 방지에는 충분하고 구현과 설명이 단순합니다.
- **Redis가 응답하지 않으면 허용합니다 (fail open).** 요청 한도 때문에 로그인과 주문 자체가 막히면 안 됩니다. 이때 Redis 클라이언트(Lettuce)의 기본 명령 타임아웃이 **60초**라는 점을 놓칠 뻔했습니다. 이대로면 Redis가 멈췄을 때 "허용"이 아니라 "요청마다 60초 대기"가 됩니다. 타임아웃을 500ms로 줄였습니다.
- **클라이언트 IP는 프록시 헤더에서 복원합니다.** 운영 프로필은 `forward-headers-strategy: framework`로 `X-Forwarded-For`를 읽습니다. Caddy는 바깥에서 들어온 이 헤더를 믿지 않고 자기가 본 IP로 덮어쓰므로, 클라이언트가 헤더를 위조해 한도를 피할 수 없습니다.
- 부하 테스트는 한 IP에서 수천 건을 보내므로 로컬에서는 꺼 두고, 운영 프로필에서 켭니다.

### 4. 서버 환경을 두 대로

[`docker-compose.yml`](../../deploy/docker-compose.yml)에 `redis`와 `app2`를 더했습니다.

- `app2`는 `app`과 같은 이미지, 같은 설정이고, **`app`이 healthy가 된 뒤에 뜹니다.** 빈 DB에서 두 대가 동시에 첫 데이터를 넣으면, `login_id` 유니크 제약과 트랜잭션 덕분에 데이터가 두 벌 생기지는 않지만 경쟁에서 진 서버는 기동에 실패하고 재시작됩니다. 기동 순서로 이 경쟁을 없앴습니다. 스키마 마이그레이션은 Flyway가 DB 락으로 한 대씩 실행합니다.
- Redis는 잃어도 되는 데이터(1분짜리 카운터, 전달 즉시 사라지는 메시지)만 두므로 디스크에 쓰지 않고, 외부 포트를 열지 않습니다.
- Caddy는 두 서버에 라운드 로빈으로 나누고, readiness가 DOWN이거나 **연결이 안 되는 서버로 간 요청은 다른 서버로 다시 보냅니다** (`lb_try_duration`, `fail_duration`). 연결 실패는 요청이 앱에 닿기 전이라 POST도 안전하게 재시도됩니다.
- 앱마다 메모리 한도를 둡니다 (`APP_MEMORY_LIMIT`, 기본 1g). 힙은 컨테이너 메모리의 75%로 잡히는데, 한도가 없으면 두 대가 각자 서버 전체 메모리의 75%를 기준으로 힙을 키웁니다.

**활동 로그 큐는 서버별로 둡니다.** 각 서버가 자기 로그를 같은 DB에 배치로 넣으므로 서버가 늘어도 문제가 없고, 서버가 죽으면 그 서버 큐에 남은 최대 300ms 분량을 잃는다는 기존 한계([#14](10-activity-log.md))만 서버마다 그대로입니다.

## 결과

자동 테스트 (H2·MySQL 양쪽):

- `UserAuthCacheTest`: 커밋 전 틈에 옛 상태가 캐시돼도 커밋 후 삭제로 다음 요청은 새 상태를 봅니다. **삭제를 커밋 전으로 되돌리면 이 테스트가 실패합니다.** 서버 여러 대를 흉내 내서, 알림을 받은 서버는 바로 새 상태를 보고 알림이 없는 서버는 옛 상태를 계속 쓰는 것을 비교합니다.
- `ClusterLockTest`: 두 서버가 같은 작업을 동시에 시작하면 한 서버만 실행합니다. 다른 서버가 주문 만료 락을 쥐고 있으면 이 서버는 건너뛰고(재고 그대로), 락이 풀리면 만료와 재고 복구를 처리합니다.
- `RateLimitTest`: 서로 다른 계정으로 돌려 가며 로그인해도 같은 IP면 한도를 넘는 순간 429입니다. 주문은 사용자별로 세서 다른 사용자는 영향이 없고, 거절된 주문은 재고를 건드리지 않습니다.

서버 환경 (CI `server-env`가 PR마다 compose를 띄워 [`smoke-test.sh`](../../deploy/smoke-test.sh) 실행):

- **서버 간 캐시 무효화**: `/api/me`를 6번 불러 두 서버 캐시를 모두 채운 뒤 관리자가 회원을 정지하면, 이어지는 6번이 모두 403이어야 합니다. 전파가 없으면 라운드 로빈으로 절반은 200이 나옵니다.
- **요청 한도 공유**: 같은 IP로 로그인 25번을 보내면 한도를 넘는 순간부터 429여야 합니다. 서버마다 따로 세면 요청이 두 서버로 나뉘어 25번 모두 통과합니다.

## 관련 코드

- [`ClusterLock`](../../backend/src/main/java/com/needsshop/common/cluster/ClusterLock.java), [`ClusterLockConfig`](../../backend/src/main/java/com/needsshop/common/cluster/ClusterLockConfig.java), [`V6__shedlock.sql`](../../backend/src/main/resources/db/migration/V6__shedlock.sql), [`OrderExpiryScheduler`](../../backend/src/main/java/com/needsshop/order/OrderExpiryScheduler.java), [`PaymentReconciler`](../../backend/src/main/java/com/needsshop/payment/PaymentReconciler.java)
- [`UserAuthCache`](../../backend/src/main/java/com/needsshop/auth/UserAuthCache.java), [`RedisClusterConfig`](../../backend/src/main/java/com/needsshop/common/cluster/RedisClusterConfig.java), [`LocalOnlyClusterConfig`](../../backend/src/main/java/com/needsshop/common/cluster/LocalOnlyClusterConfig.java)
- [`RateLimitFilter`](../../backend/src/main/java/com/needsshop/common/ratelimit/RateLimitFilter.java), [`RedisRateLimiter`](../../backend/src/main/java/com/needsshop/common/ratelimit/RedisRateLimiter.java), [`InMemoryRateLimiter`](../../backend/src/main/java/com/needsshop/common/ratelimit/InMemoryRateLimiter.java)
- [`deploy/docker-compose.yml`](../../deploy/docker-compose.yml), [`frontend/Caddyfile`](../../frontend/Caddyfile)
