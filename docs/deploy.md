# 서버 환경 가이드

## 환경은 둘로 나뉩니다

| | 테스트 환경 | 서버 환경 |
|---|---|---|
| 목적 | 코드를 바꿀 때마다 정합성을 빠르게 검증 | 실제 서버 구성으로 띄우고 배포 |
| 필요한 것 | JDK 21, Node 22 (Docker 불필요) | Docker (Compose) |
| 실행 | `./gradlew test`, `npm run build`, CI의 `backend`·`frontend` 작업 | `deploy/`의 `docker compose`, CI의 `server-env` 작업 |
| DB | H2 (또는 `-Pdb=mysql`로 로컬 MySQL) | MySQL 컨테이너 |
| 확인하는 것 | 동시성, 결제 장애, 멱등성, 인가 등 자동 테스트 | 운영 설정 안전장치, HTTPS 프록시, 컨테이너 헬스체크, 프록시를 거친 실제 주문·결제 흐름 |

서버가 없어도 테스트 환경은 언제든 돌릴 수 있고, 서버 환경은 Docker가 있는 곳(로컬, CI, 클라우드 서버)이면 같은 파일로 띄웁니다.

## 구성

```
           :443 (HTTPS)                     내부 네트워크
브라우저 ───────────────▶ web (Caddy) ──/api/*──▶ app (Spring Boot, prod 프로필) ──▶ mysql (MySQL 8)
                          │  인증서 자동 발급                │ readiness 헬스체크
                          └─ 정적 파일 (React 빌드 결과)       └─ 내장 Mock PG (HTTP)
```

| 서비스 | 이미지 | 하는 일 |
|---|---|---|
| `web` | [`frontend/Dockerfile`](../frontend/Dockerfile) (React 빌드 + Caddy) | HTTPS, 정적 파일, `/api` 프록시. 앱의 readiness가 DOWN이면 그 서버로 보내지 않음 ([`Caddyfile`](../frontend/Caddyfile)) |
| `app` | [`backend/Dockerfile`](../backend/Dockerfile) | root가 아닌 사용자로 실행, 컨테이너 메모리 비율로 힙 설정, readiness 헬스체크 |
| `mysql` | `mysql:8.0` | 외부 포트를 열지 않음. 앱은 스키마 전용 계정(`flashdeal`)으로 접속 |

`actuator`와 `mock-pg`는 프록시하지 않아서 외부에서 보이지 않습니다.

## 운영 프로필(prod)이 막는 것

`SPRING_PROFILES_ACTIVE=prod`이면 [`ProductionGuard`](../backend/src/main/java/com/flashdeal/common/config/ProductionGuard.java)가 다른 빈이 만들어지기 전에 설정을 검사하고, 하나라도 걸리면 **서버가 뜨지 않습니다.**

| 검사 | 이유 |
|---|---|
| `JWT_SECRET`이 없거나 32바이트 미만 | 저장소에 공개된 데모 키로 누구나 토큰을 위조할 수 있음 |
| `ADMIN_PASSWORD`가 없거나 12자 미만, 또는 `admin` | 관리자 계정 탈취 |
| 데모 회원(user1, user2) 생성이 켜져 있음 | 비밀번호가 문서에 공개되어 있음 |
| DB가 H2 | 재시작하면 주문과 결제가 사라짐 |
| 개발 도구(재고 전략 전환 API)가 켜져 있음 | NAIVE 전략은 초과 판매가 남 |

그 밖에 prod 프로필은 MySQL 프로필을 함께 켜고(`spring.profiles.group`), Swagger를 끄고(`SWAGGER_ENABLED=true`로 켬), actuator는 `health`·`info`만 노출하고, 로그를 JSON(ECS)으로 남깁니다. 로그 한 줄에 `traceId`, `userId`가 필드로 들어갑니다.

## 실행 (로컬이든 서버든 같습니다)

```bash
cd deploy
cp .env.example .env          # 비밀값 채우기. 임의 값: openssl rand -base64 32
docker compose up -d --build  # 처음에는 이미지 빌드로 몇 분 걸립니다
docker compose ps             # app이 healthy가 되면 준비 완료
ADMIN_PASSWORD=<.env의 값> ./smoke-test.sh   # https://localhost 기준
```

| 하고 싶은 것 | 명령 |
|---|---|
| 로그 보기 | `docker compose logs -f app` (JSON) / `web` (접속 로그) |
| 멈추기 / 다시 띄우기 | `docker compose stop` / `docker compose up -d` |
| 코드 바꾼 뒤 다시 빌드 | `docker compose up -d --build app` |
| 내리기 (데이터 유지) | `docker compose down` |
| 데이터까지 지우기 | `docker compose down -v` (DB와 발급받은 인증서가 모두 지워집니다) |
| DB 백업 | `docker compose exec mysql sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" flashdeal' > backup.sql` |

- 로컬 PC에 MySQL이 이미 있어도 충돌하지 않습니다. 서버 환경의 MySQL은 포트를 열지 않습니다.
- 80/443 포트가 이미 쓰이고 있으면 `.env`의 `HTTP_PORT`, `HTTPS_PORT`를 바꿉니다.
- `SITE_ADDRESS=localhost`는 Caddy 내부 인증서라 브라우저가 경고를 띄웁니다. 도메인을 쓰면 실제 인증서가 자동으로 발급됩니다.

### 스모크 테스트가 확인하는 것

[`smoke-test.sh`](../deploy/smoke-test.sh)는 bash와 curl만으로 프록시를 거쳐 한 바퀴 돕니다. 서버에서도 그대로 돌릴 수 있습니다.

1. 프록시 → 앱 연결, 화면과 SPA 라우팅
2. actuator가 외부에 노출되지 않음
3. 데모 회원이 없음 (user1 로그인 401), `ADMIN_PASSWORD`를 주면 관리자 로그인
4. 가입 → 주문 → **같은 Idempotency-Key로 재요청하면 같은 주문이 재생됨** → 결제 승인

CI의 `server-env` 작업이 모든 PR과 `main` push에서 이 과정(이미지 빌드 → compose 기동 → 스모크 테스트)을 그대로 실행합니다.

## 클라우드 서버에 올리기

서버 한 대(메모리 2GB 이상 권장, Docker 설치)면 됩니다. 방화벽(보안 그룹)은 80, 443만 열고 SSH는 내 IP만 허용합니다.

### 방법 1: 서버에서 직접 빌드

```bash
git clone https://github.com/parkhh16/flashdeal.git && cd flashdeal/deploy
cp .env.example .env    # SITE_ADDRESS에 도메인
docker compose up -d --build
./smoke-test.sh https://<도메인>
```

이미지 빌드(Gradle)에 메모리를 많이 써서 1GB 서버에서는 빌드가 실패할 수 있습니다. 그럴 때는 방법 2를 씁니다.

### 방법 2: 배포 워크플로 (이미지를 CI에서 빌드)

[`deploy.yml`](../.github/workflows/deploy.yml)은 수동 실행 전용입니다 (Actions → Deploy → Run workflow).

1. 앱·웹 이미지를 GHCR(`ghcr.io/<계정>/flashdeal-app`, `-web`)에 올립니다.
2. 저장소 Secrets에 `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`가 있으면 SSH로 서버의 `~/flashdeal/deploy`에 compose 파일을 올리고 새 이미지로 교체합니다. 서버의 `.env`는 건드리지 않습니다.
3. 입력한 주소가 있으면 GitHub 러너에서 그 주소로 스모크 테스트를 돌립니다.

서버 준비:
- `~/flashdeal/deploy/.env`를 미리 만들어 둡니다 (`IMAGE_PREFIX=ghcr.io/<계정>/flashdeal`, `IMAGE_TAG=latest`도 넣어 두면 서버에서 직접 `docker compose up -d`를 해도 같은 이미지를 씁니다).
- GHCR 패키지가 비공개면 서버에서 한 번 `docker login ghcr.io`가 필요합니다 (`read:packages` 권한 토큰).

Secrets가 없으면 이미지 푸시까지만 하고 끝납니다.

## 주의

- 켜 둔 시간만큼 비용이 듭니다. 확인이 끝나면 서버를 **종료(삭제)** 합니다. 중지만 하면 디스크와 고정 IP 요금이 남을 수 있습니다.
- `.env`는 서버에만 둡니다. 저장소에 올리지 않습니다 (`.gitignore`).
- 결제는 내장 Mock PG입니다. 실제 결제 대행사 연동은 하지 않았습니다.
