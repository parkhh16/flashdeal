# 데모 기본값으로 "잘 뜨는" 운영 서버를 막기: 설정 안전장치와 서버 환경 분리

## 문제

로컬에서 바로 실행되도록 기본값을 넉넉하게 두었습니다. JWT 서명 키는 저장소에 있는 데모 키, 관리자는 `admin/admin`, 비밀번호가 문서에 공개된 데모 회원 2명, DB는 메모리(H2)입니다. 운영에서는 환경변수로 덮어쓰라고 문서에 적어 두었습니다.

문제는 **환경변수를 하나 빠뜨려도 서버가 아무 일 없다는 듯 뜬다**는 점입니다. 실제로 점검해 보니 이미 하나가 빠져 있었습니다.

> README와 가이드에는 "운영에서는 `ADMIN_PASSWORD`로 관리자 비밀번호를 바꾼다"고 적혀 있었지만, 코드는 `flashdeal.seed.admin-password` 속성을 읽고 있었고 `ADMIN_PASSWORD`는 어디에도 연결되어 있지 않았습니다. 이 환경변수를 설정해도 관리자는 `admin/admin` 그대로였습니다.

공개된 JWT 키로 뜬 서버는 누구나 관리자 토큰을 만들 수 있고, `admin/admin`이면 관리자 화면이 그대로 열립니다. 이런 서버가 "정상 동작"하는 것이 가장 위험합니다.

## 해결

### 1. 연결 버그 수정과 회귀 테스트

`application.yml`에서 `flashdeal.seed.admin-password: ${ADMIN_PASSWORD:admin}`으로 연결하고, 환경변수가 실제로 그 속성에 들어가는지 확인하는 테스트를 추가했습니다. 문서에 적힌 설정이 코드에서 동작하는지는 테스트로만 지킬 수 있습니다.

### 2. 운영 프로필은 안전하지 않으면 기동하지 않는다 (fail fast)

`prod` 프로필이면 [`ProductionGuard`](../../backend/src/main/java/com/flashdeal/common/config/ProductionGuard.java)가 설정을 검사합니다. 하나라도 걸리면 이유를 모두 모아서 기동을 중단합니다.

| 검사 | 막는 것 |
|---|---|
| `JWT_SECRET` 없음 또는 32바이트 미만 | 공개된 키로 토큰 위조 |
| `ADMIN_PASSWORD` 없음, 12자 미만, `admin` | 관리자 계정 탈취 |
| 데모 회원 생성이 켜짐 | 공개된 비밀번호의 계정 |
| DB가 H2 | 재시작하면 주문·결제 유실 |
| 개발 도구(재고 전략 전환 API)가 켜짐 | NAIVE 전략의 초과 판매 |

`BeanFactoryPostProcessor`로 만든 이유는 **다른 빈이 만들어지기 전에** 검사하기 위해서입니다. 일반 빈으로 두면 `JwtProvider`가 먼저 빈 키로 생성되면서 원인을 알기 어려운 예외(`WeakKeyException`)로 죽습니다. 지금은 아래처럼 무엇을 고쳐야 하는지가 그대로 나옵니다.

```
prod 프로필 기동 중단 - 운영 설정이 안전하지 않습니다:
 - JWT_SECRET이 없거나 256bit(32바이트)보다 짧습니다. ...
 - ADMIN_PASSWORD가 없거나 12자보다 짧습니다 ...
```

운영 프로필은 이 밖에 데모 회원 없이 관리자만 시드하고, Swagger를 끄고, actuator는 `health`·`info`만 노출하고, 로그를 JSON(ECS)으로 남깁니다.

### 3. 테스트 환경과 서버 환경을 분리하고, 서버 환경도 CI에서 매번 띄운다

| | 테스트 환경 | 서버 환경 |
|---|---|---|
| 필요 | JDK, Node | Docker |
| 실행 | `./gradlew test` (H2 / MySQL) | `deploy/docker-compose.yml` (MySQL + 앱 + Caddy) |
| 확인 | 동시성, 결제 장애, 멱등성 등 | 운영 프로필 기동, HTTPS 프록시, 헬스체크, 프록시를 거친 주문·결제 |

서버 환경은 [`smoke-test.sh`](../../deploy/smoke-test.sh)로 확인합니다. 화면, 내부 경로 비노출, 데모 회원 부재, 가입, 주문, **같은 멱등 키 재요청의 재생**, 결제까지 bash와 curl만으로 돕니다. CI의 `server-env` 작업이 PR마다 이미지를 빌드하고 compose를 띄워 이 스크립트를 실행합니다.

## 결과

- 데모 기본값을 하나라도 남긴 채로는 운영 프로필이 뜨지 않습니다 (`ProductionGuardTest`: 기동 거부, 항목별 검출, 짧은 키·Base64가 아닌 키, `ADMIN_PASSWORD` 연결).
- 운영 프로필로 새 MySQL 스키마에 띄우면 마이그레이션 V1~V5가 적용되고, 관리자 1명과 상품만 시드됩니다. 그 앞에 Caddy를 두고 스모크 테스트가 처음부터 끝까지 통과했습니다.
- 로그 한 줄에 `traceId`와 `userId`가 JSON 필드로 남아서 로그 수집기에서 바로 검색할 수 있습니다.

## 관련 코드

- [`ProductionGuard`](../../backend/src/main/java/com/flashdeal/common/config/ProductionGuard.java), [`application.yml`](../../backend/src/main/resources/application.yml) (`prod` 프로필)
- [`deploy/docker-compose.yml`](../../deploy/docker-compose.yml), [`frontend/Caddyfile`](../../frontend/Caddyfile), [`backend/Dockerfile`](../../backend/Dockerfile)
- [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) (`server-env`), [`deploy.yml`](../../.github/workflows/deploy.yml)
- 테스트: `ProductionGuardTest`
- 실행 방법: [서버 환경 가이드](../deploy.md)
