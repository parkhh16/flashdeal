# 탈퇴·정지한 회원의 JWT가 계속 살아 있는 문제: 회원·인증 설계

클릭 한 번으로 로그인되던 데모 로그인을 실제 회원가입·로그인·탈퇴 절차로 바꾸면서 부딪힌 문제들입니다.

## 1. JWT 즉시 무효화: `token_version` + 캐시

### 문제

JWT는 서버에 상태를 두지 않아서, 회원이 탈퇴하거나 관리자가 정지·강제 로그아웃을 해도 **이미 발급된 토큰은 만료(2시간)까지 그대로 쓸 수 있습니다.**

### 해결

- 사용자마다 `token_version`을 두고 토큰에도 넣습니다. 요청마다 토큰의 버전과 현재 버전을 비교합니다.
- 탈퇴·정지·강제 로그아웃·"모든 기기에서 로그아웃" 시 버전을 1 올립니다. **기존 토큰은 다음 요청부터 바로 거부됩니다.**
- 역할(ADMIN)도 토큰 값이 아니라 현재 사용자 정보로 판단해서, 권한을 회수하면 바로 반영됩니다.

### 요청마다 DB를 보면 JWT를 쓰는 의미가 없지 않나?

그래서 DB가 아니라 **Caffeine 로컬 캐시(30초)** 를 봅니다. 사용자 상태를 바꾸는 쪽이 캐시를 즉시 지우므로 같은 서버에서는 바로 반영되고, 서버가 여러 대라면 다른 서버는 최대 30초 늦게 반영됩니다. 즉시 무효화와 성능 사이의 절충이고, 다중 서버에서는 Redis pub/sub으로 캐시 삭제를 전파하는 것이 다음 단계입니다.

정지된 계정은 401이 아니라 403("이용이 정지된 계정")으로 응답해서, 사용자가 왜 로그인이 풀렸는지 알 수 있게 했습니다.

## 2. 로그인 무차별 대입: 5회 실패 시 5분 잠금

실패 횟수를 저장하고 예외를 던지면, **예외 때문에 트랜잭션이 롤백되면서 실패 횟수도 같이 사라집니다.** `@Transactional(noRollbackFor = BusinessException.class)`로 로그인 실패 예외가 나도 실패 횟수는 커밋되게 했습니다.

- 관리자는 회원 관리 화면에서 잠금을 풀 수 있습니다.
- 없는 아이디와 틀린 비밀번호는 **같은 에러 코드**로 응답해서 계정이 있는지 없는지 알 수 없게 했습니다. 정지 여부는 비밀번호가 맞은 뒤에만 알려줍니다.

## 3. 같은 아이디로 동시에 가입: 유니크 제약이 최종 방어

"아이디 중복 확인 → 저장" 사이에 다른 요청이 끼어들면 둘 다 중복 확인을 통과합니다. 중복 확인은 화면에서 바로 알려주기 위한 용도로만 쓰고, **최종 방어는 DB 유니크 제약**에 맡겼습니다.

제약 위반이 나면 그 트랜잭션의 세션이 깨져서 "무엇이 중복인지" 다시 조회할 수 없습니다. 그래서 위반 메시지에 담긴 **제약(컬럼) 이름**으로 아이디 중복인지 이메일 중복인지 판단해 409로 응답합니다.

→ 같은 아이디로 10명이 동시에 가입해도 **정확히 1명만 가입** (`AuthFlowTest.concurrentSignup`)

## 4. 회원 탈퇴: 행은 남기고 개인정보는 지운다

주문·결제 기록은 전자상거래법상 보관 의무가 있고 외래 참조도 걸려 있어서 행을 지울 수 없습니다.

- 이름·이메일·아이디를 익명화하고, 비밀번호는 무작위 값으로 바꿉니다 (소프트 삭제).
- 결제 대기·결제 중인 주문이 있으면 탈퇴를 막습니다. 돈이 오가는 중에 계정이 사라지면 안 되기 때문입니다.
- 탈퇴 시 비밀번호를 다시 확인하고, 탈퇴 즉시 기존 토큰은 거부됩니다.
- 익명화로 아이디가 비워지므로, 같은 아이디로 다시 가입할 수 있습니다.

## 5. 그 밖에

- **로그인 후 원래 화면으로 복귀**: `next` 파라미터는 앱 내부 경로(`/`로 시작하고 `//`가 아닌 것)만 허용해서 오픈 리다이렉트를 막았습니다.
- **권한은 전부 서버에서 검사**합니다. 남의 주문은 403이 아니라 **404**(존재 여부를 알려주지 않음), 남의 활동 조회는 **403**(IDOR 방지), 관리자 API는 `hasRole("ADMIN")`. 프론트엔드의 메뉴 숨김은 편의일 뿐입니다.
- **공통 에러 응답** `{code, message, traceId}`: 클라이언트는 HTTP 상태가 아니라 도메인 에러 코드로 분기합니다.
- 데모 관리자 계정은 `admin / admin`이고, 운영에서는 `ADMIN_PASSWORD`와 `JWT_SECRET` 환경변수로 반드시 바꿔야 합니다.

## 관련 코드

- [`JwtAuthenticationFilter`](../../backend/src/main/java/com/flashdeal/auth/JwtAuthenticationFilter.java): 서명 검증 + 토큰 버전·정지 확인
- [`UserAuthCache`](../../backend/src/main/java/com/flashdeal/auth/UserAuthCache.java): 사용자 상태 캐시 (30초, 변경 시 즉시 삭제)
- [`AuthService`](../../backend/src/main/java/com/flashdeal/auth/AuthService.java): 가입, 로그인 잠금, 탈퇴
- [`User`](../../backend/src/main/java/com/flashdeal/auth/User.java): `withdraw`(익명화), `token_version`
- [`AdminUserService`](../../backend/src/main/java/com/flashdeal/admin/AdminUserService.java): 정지·해제, 강제 로그아웃, 잠금 해제
- [`V4__user_account.sql`](../../backend/src/main/resources/db/migration/V4__user_account.sql)
- 테스트: `AuthFlowTest` (`concurrentSignup`, `lockout`, `noUserEnumeration`, `withdraw`, `adminControls`), `OrderApiTest.authorization`, `ActivityLogTest.authorization`
