# 실행 가이드

실행, 테스트, 부하 테스트, 설정, 기능 둘러보기 순서를 정리한 문서입니다.

## 1. 준비물

- JDK 21 (Gradle Wrapper 포함, Gradle 설치 불필요)
- Node.js 22 이상 (프론트엔드, 부하 테스트)
- MySQL 8 (선택). 없으면 H2 인메모리로 실행됩니다.

## 2. 실행

```bash
# 백엔드: http://localhost:8080  (데모 계정 3개, 상품 25개 자동 생성)
cd backend
./gradlew bootRun                                   # H2 인메모리 (재시작하면 데이터 초기화)
SPRING_PROFILES_ACTIVE=mysql DB_USER=root DB_PASSWORD=<비밀번호> ./gradlew bootRun   # MySQL 8

# jar로 실행
./gradlew bootJar
java -jar build/libs/flashdeal-0.0.1.jar

# 프론트엔드: http://localhost:5173  (/api는 8080으로 프록시)
cd frontend
npm install
npm run dev
```

- MySQL 프로파일은 `flashdeal` 스키마가 없으면 만들고, Flyway 마이그레이션(V1~V4)을 적용하고, 회원 테이블이 비어 있을 때만 데모 데이터를 넣습니다.
- `ANTHROPIC_API_KEY`를 설정하면 AI 검색이 Claude API를 사용합니다. 없으면 규칙 기반 파서로 동작합니다.
- `docker compose up --build`로 앱과 MySQL을 함께 띄우는 구성도 있습니다 (`docker-compose.yml`). 로컬에 Docker가 없어 직접 실행해 보지는 못했습니다.

| URL | 용도 |
|---|---|
| http://localhost:5173 | 쇼핑몰 (카테고리, 상품 상세, AI 검색) |
| http://localhost:5173/me | 내 활동 (주문·결제, 최근 검색어, 최근 본 상품) |
| http://localhost:5173/login · /signup · /me/account | 로그인, 회원가입, 계정 설정 (모든 기기 로그아웃, 탈퇴) |
| http://localhost:5173/admin | 관리자 (대시보드, 활동 로그, 회원, 상품, 주문, 테스트 실험실) |
| http://localhost:8080/swagger-ui.html | API 문서 |
| http://localhost:8080/actuator/health | 헬스체크 |

### 데모 계정

| 아이디 | 비밀번호 | 권한 |
|---|---|---|
| user1 | user1234! | USER (김철수) |
| user2 | user1234! | USER (이영희) |
| admin | admin | ADMIN |

- 아이디 또는 이메일로 로그인합니다. 로그인 화면의 "데모 계정" 버튼은 입력칸만 채워줍니다.
- 회원가입 규칙: 아이디는 영문 소문자·숫자·`_` 4~20자, 비밀번호는 영문과 숫자를 포함해 8자 이상
- 로그인에 5회 실패하면 5분 동안 잠깁니다. 관리자 → 회원 관리에서 잠금을 풀 수 있습니다.
- `admin / admin`은 데모용입니다. 운영이라면 `ADMIN_PASSWORD`(처음 시드될 때만 적용)와 `JWT_SECRET` 환경변수를 반드시 설정해야 합니다.

## 3. 테스트

```bash
cd backend
./gradlew test                                          # 전체 69개, H2 (약 2분)
DB_PASSWORD=<비밀번호> ./gradlew test -Pdb=mysql         # 같은 테스트를 MySQL flashdeal_test 스키마에서

./gradlew test --tests "*StockConcurrencyTest"          # 재고 전략별 동시성 비교표 출력
./gradlew test --tests "*PaymentFailureScenarioTest"    # 결제 장애 시나리오
./gradlew test --tests "*OrderApiTest"                  # 멱등성, 인가, traceId
./gradlew test --tests "*PurchaseRuleTest"              # 1인 구매 제한 (동시 주문 포함), 판매 기간
./gradlew test --tests "*AdminFeaturesTest"             # 취소·환불, 재고 증감 조정, @DynamicUpdate
./gradlew test --tests "*AuthFlowTest"                  # 가입, 로그인 잠금, 탈퇴, 정지, 강제 로그아웃, 동시 가입
./gradlew test --tests "*AiSearchServiceTest"           # LLM 캐시, 폴백, 스탬피드 방지
./gradlew test --tests "*OrderQueryPerformanceTest"     # N+1 쿼리 수, 실행 계획

cd ../frontend && npm run build                         # 타입 체크 + 빌드
```

- 동시성 비교표는 `backend/build/test-results/test/TEST-com.flashdeal.order.StockConcurrencyTest.xml`의 system-out에 있습니다.
- HTML 리포트: `backend/build/reports/tests/test/index.html`
- `-Pdb=mysql`은 테스트 전용 스키마(`flashdeal_test`)를 쓰므로 앱 데이터와 섞이지 않습니다.

## 4. 부하 테스트

서버를 띄운 상태에서 실행합니다.

```bash
node loadtest/order-rush.mjs --all --stock 2000 --requests 2000   # 모든 요청이 재고 행을 두고 경합 (전략 비교용)
node loadtest/order-rush.mjs --all                                # 재고 100 / 요청 2,000 (대부분 품절, 정합성 확인용)
node loadtest/order-rush.mjs --strategy ATOMIC_UPDATE --requests 5000 --concurrency 200
```

| 옵션 | 기본값 | 설명 |
|---|---|---|
| `--all` | - | NAIVE, PESSIMISTIC, OPTIMISTIC, ATOMIC_UPDATE 순서로 전부 실행 |
| `--strategy` | 현재 설정 | 전략 하나만 실행 |
| `--requests` | 2000 | 총 주문 요청 수 |
| `--concurrency` | 100 | 동시 요청 수 |
| `--stock` | 100 | 전용 테스트 상품의 초기 재고 |
| `--users` | 200 | 미리 가입시킬 사용자 수 |
| `--base` | http://localhost:8080 | 대상 서버 |

- 전략마다 **전용 상품(`[부하 테스트] 전략명 시각`)을 새로 만들고, 끝나면 판매 중지**합니다. 기존 상품의 재고와 판매량은 건드리지 않습니다.
- 측정 편차가 큽니다 (JVM 워밍업, PC 상태). 전략을 비교할 때는 같은 조건으로 **최소 2번** 돌리고 일관된 차이만 봅니다.
- 실행할 때마다 `load_` 회원 200명과 주문 수천 건이 쌓입니다. 깨끗한 상태에서 다시 재려면 아래처럼 스키마를 지우고 앱을 다시 띄웁니다.
  ```bash
  mysql -uroot -p -e "drop database flashdeal; drop database flashdeal_test;"
  ```

## 5. 주요 설정 (`backend/src/main/resources/application.yml`)

| 키 | 기본값 | 설명 |
|---|---|---|
| `flashdeal.stock.strategy` | ATOMIC_UPDATE | 재고 차감 전략 (관리자 테스트 실험실에서 실행 중에 변경 가능) |
| `flashdeal.order.reservation-ttl` | 10m | 결제 대기 재고 선점 시간 |
| `flashdeal.pg.base-url` | (빈 값 = 내장 Mock PG) | 실제 PG URL (`PG_BASE_URL`) |
| `flashdeal.pg.connect-timeout` / `read-timeout` | 1s / 2s | PG 타임아웃 |
| `flashdeal.pg.max-attempts` | 3 | PG 승인 재시도 횟수 (200ms, 400ms 간격) |
| `flashdeal.payment.reconcile-grace` | 5s | 대사 대상이 되기까지 기다리는 시간 |
| `flashdeal.ai.api-key` | `ANTHROPIC_API_KEY` | 없으면 규칙 기반 파서 |
| `flashdeal.ai.model` | claude-opus-5 | 질의 해석 모델 |
| `flashdeal.ai.timeout` | 8s | LLM 타임아웃 (재시도 없이 바로 폴백) |
| `flashdeal.jwt.secret` | 데모 키 | 운영에서는 `JWT_SECRET`으로 반드시 교체 |
| `flashdeal.demo.rotate-deals` | true | 끝난 데모 특가를 5분마다 같은 시간표로 다시 편성 (운영에서는 false) |
| `flashdeal.seed.admin-password` | admin | 시드 관리자 비밀번호 (`ADMIN_PASSWORD`) |
| `spring.jpa.hibernate.ddl-auto` | validate | 스키마는 Flyway가 관리 |
| `spring.jpa.properties.hibernate.default_batch_fetch_size` | 100 | N+1 해결 |
| `spring.jpa.open-in-view` | false | 트랜잭션 밖 지연 로딩 차단 |

### 스키마 변경 (Flyway)

- `backend/src/main/resources/db/migration/`에 다음 번호의 파일(`V5__설명.sql`)을 추가하고 엔티티를 맞춥니다. 서버를 띄울 때 자동으로 적용되고, JPA `validate`가 엔티티와 스키마의 불일치를 잡아냅니다.
- 이미 적용된 V1~V4 파일은 수정하지 않습니다 (체크섬 불일치로 기동 실패).
- H2와 MySQL 양쪽에서 동작하는 문법만 씁니다 (예: `ALTER TABLE ... ADD COLUMN`은 한 문장에 하나씩).

## 6. 상품 이미지

```bash
node scripts/fetch-product-images.mjs
```

- Wikimedia Commons에서 파일명을 고정해 둔 사진을 받아 800×800으로 자르고, `IMAGE_CREDITS.md`와 매핑 파일을 갱신합니다.
- 저장 위치: `frontend/public/images/products/`, 상품 매핑: `backend/src/main/resources/seed/product-images.json`
- 자유 라이선스(CC0, CC BY, CC BY-SA, PD)가 아닌 파일은 자동으로 건너뜁니다. ffmpeg가 없으면 원본을 그대로 저장합니다.

## 7. API 예시 (curl)

```bash
# 로그인
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"loginId":"user1","password":"user1234!"}' | jq -r .accessToken)

# 상품 목록 (필터, 정렬)
curl 'localhost:8080/api/products?category=MOUSE&sort=PRICE_ASC&excludeSoldOut=true'
curl 'localhost:8080/api/products?deal=true&sort=DEADLINE'

# 주문 (Idempotency-Key 필수)
curl -X POST localhost:8080/api/orders -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: my-key-1' -d '{"items":[{"productId":2,"quantity":1}]}'

# 결제
curl -X POST localhost:8080/api/orders/1/payment -H "Authorization: Bearer $TOKEN" -H 'Idempotency-Key: pay-1'

# AI 검색 (Windows curl은 한글이 깨질 수 있어 --data-urlencode 사용)
curl -G localhost:8080/api/products/search --data-urlencode 'q=5만원 이하 무선 마우스'
```

전체 API 목록은 [README](../README.md#api)와 Swagger UI에 있습니다.

## 8. 기능 둘러보기 (5분)

1. **쇼핑몰**: 사이드바에서 "오늘의 특가" → 마감이 임박한 헤드셋 상세 → 카운트다운, 한정 수량 진행바, "1인 1개" 확인
2. **AI 검색**: "5만원 이하 무선 마우스" 검색 → "이렇게 이해했어요" 조건 칩 → 칩 하나를 빼면 다시 검색. "마으수"처럼 오타를 내면 "마우스"로 교정된 결과
3. **1인 구매 제한**: user1로 헤드셋 1개 구매 → 한 번 더 구매하면 "1인 1개까지" 안내
4. **관리자 → 테스트 실험실**
   - 재고 전략을 "락 없음"으로 바꾸고 재고 100 / 동시 300으로 실행 → **초과 판매 발생** → "원자적 UPDATE"로 바꿔 다시 실행 → **0건**
   - 멱등성: 같은 키로 5개 동시 요청 → 주문 1건
   - 결제 장애 "PG 승인 후 우리 DB 장애"를 켜고 user1로 결제 → "결제 확인 중" → 관리자에서 결제 대사 실행 → **결제 완료로 복구** → 정합성 점검 0건 → 장애 설정을 "정상"으로 되돌리기
5. **관리자 → 활동 로그**: 사용자 "김철수" 선택 → 방금 한 검색, 조회, 주문, 결제 흐름과 traceId 확인
6. **관리자 → 주문 관리**: 결제 완료 주문 "환불·취소" → 결제 상태 "환불 완료", 재고 복구
7. **JWT 즉시 무효화**: 브라우저 창 두 개(하나는 시크릿 창)에서 한쪽은 새로 가입한 회원, 한쪽은 admin으로 로그인 → 관리자 회원 관리에서 그 회원을 "정지" → 회원 창에서 페이지를 이동하면 토큰 만료를 기다리지 않고 **바로 "이용이 정지된 계정" 안내와 함께 로그인 화면으로** 이동

## 9. 커밋 컨벤션

`feat(scope): …`, `fix(scope): …`, `refactor: …`, `test: …`, `ci: …`, `docs: …`, `chore: …`

- `.gitattributes`로 줄바꿈을 LF로 고정합니다 (Windows에서 커밋해도 `gradlew`가 CI의 Linux에서 실행되도록).
