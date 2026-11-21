@docs/LESSONS.md

# 니즈샵

재고가 한정된 상품에 주문이 몰려도 재고와 돈이 어긋나지 않는 쇼핑몰 주문·결제 시스템. 개인 포트폴리오 프로젝트이고, "문제 재현 → 원인 분석 → 해결 → 수치 확인"을 문서로 남기는 것이 목적이다.

## 구조
- `backend/`: Java 21, Spring Boot 3.5, JPA, Flyway, MySQL 8(운영/검증) + H2(로컬/기본 테스트), Redis(서버 여러 대일 때만), ShedLock
- `frontend/`: React 19, TypeScript, Vite
- `deploy/`: Docker Compose(MySQL, Redis, 앱 2대, Caddy), `smoke-test.sh`
- `loadtest/`: 부하 테스트(Node)
- `docs/issues/`: 기술 이슈 해결 문서 (번호순)

## 명령어
```bash
cd backend
./gradlew test                                      # 전체 테스트 (H2, 오래 걸림)
./gradlew test --tests "*StockConcurrencyTest"      # 클래스 하나만
DB_PASSWORD=<비밀번호> ./gradlew test -Pdb=mysql     # 같은 테스트를 MySQL에서
./gradlew compileJava compileTestJava               # 컴파일만 빠르게 확인
cd ../frontend && npm ci && npm run build           # 프론트 타입 체크 + 빌드
```

## 지켜야 할 설계 규칙
1. **재고는 덮어쓰지 않고 증감한다.** 차감은 `UPDATE ... SET stock = stock - ? WHERE stock >= ?` 한 문장으로, 복구와 관리자 조정은 `stock = stock + :delta`로 한다.
2. **동시성 제어는 충돌 빈도에 따라 다르게 쓴다.** 재고는 원자적 UPDATE, 주문 상태 변경은 낙관적 락(`@Version`), 1인 구매 제한은 사용자 행 락. 락 순서는 항상 사용자 → 상품(id 오름차순).
3. **주문 생성 트랜잭션은 `READ_COMMITTED`로 명시한다.** MySQL 기본 REPEATABLE READ에서는 락을 기다리는 사이 고정된 스냅샷을 읽어 제한이 뚫린다.
4. **중복 방지는 DB 유니크 제약으로 한다.** 서버가 여러 대여도 동작해야 한다 (멱등 키, 동시 회원가입).
5. **외부 결제(PG) 호출은 트랜잭션 밖에서 한다.** 타임아웃은 실패가 아니라 `UNKNOWN`이고, 대사 배치(`PaymentReconciler`)가 PG 조회로 확정한다.
6. **PAYING 주문은 만료 스케줄러도 관리자 취소도 건드리지 않는다.** 결제 결과를 모르는 채 재고를 풀면 돈만 빠진 주문이 생긴다. 환불은 PG 환불이 성공한 뒤에만 우리 쪽 상태를 바꾼다.
7. **서버 메모리에 기댄 상태는 서버 2대 기준으로 따진다.** 스케줄러는 ShedLock으로 한 서버에서만, 사용자 캐시는 커밋 후 삭제하고 Redis로 전파, 요청 한도는 공유 카운터.
8. **부가 기능이 핵심 흐름을 막지 않는다.** 활동 로그는 비동기 배치(실패 시 버림), AI(Gemini)는 요청 밖의 백그라운드 작업에서만 호출하고, 검색은 키가 없어도 동작한다.
9. **운영 프로필(`prod`)은 안전하지 않으면 기동하지 않는다** (`ProductionGuard`). 데모 키, `admin/admin`, H2, 개발 도구가 남아 있으면 안 된다. 이 가드를 약하게 만들지 않는다.
10. **스키마 변경은 새 Flyway 마이그레이션으로만 한다.** 기존 버전은 수정하지 않는다 (`ddl-auto: validate`).
11. **테스트는 운영과 같은 DB(MySQL)에서도 통과해야 한다.** 동시성, 락, 격리 수준을 건드린 변경은 H2 통과만으로 끝내지 않는다.

## 에이전트 사용 흐름 (`.claude/agents/`)
- `coder`: 구현. 최소 변경만.
- `reviewer`: 읽기 전용 리뷰. 주문, 재고, 결제, 인증을 바꾸면 반드시 거친다.
- `tester`: 테스트 작성과 실행. 동시성 변경은 MySQL 검증까지.

프론트(`frontend/`) UI 라운드는 `designer` → `frontend-coder` → `frontend-tester`·`frontend-reviewer`·`visual-reviewer` → `lessons-curator` 순서이고, 라운드 시작 전 `docs/LESSONS.md`의 규칙(에이전트 수·포그라운드·끝에 커밋 등)을 따른다.

기본 흐름은 `coder` → `tester` → `reviewer`이고, reviewer가 지적하면 coder가 고친 뒤 tester를 다시 돌린다. 한 번에 한 가지 문제만 다룬다.

## 작업 완료 기준
- 관련 테스트 통과. 전체 `./gradlew test` 통과(마지막에 한 번).
- 성능, 동시성 개선은 **전후 수치**를 남긴다 (쿼리 수, TPS, p99, 초과 판매 건수 등).
- 설계가 달라지는 변경이면 `docs/issues/`에 문서를 추가하거나 갱신한다. 형식은 `문제 → 원인 → 해결 → 결과(Before/After 표) → 관련 코드`로, 기존 문서(`docs/issues/02-purchase-limit.md`)를 따른다.
- README의 "주요 결과" 표는 측정으로 확인한 숫자만 쓴다.

## 커밋과 PR
- 커밋 메시지는 `feat:`, `fix:`, `refactor:`, `test:`, `docs:` 접두어 + 한국어 한 줄.
- `main`에 직접 쓰지 않고 브랜치로 작업한 뒤 PR을 연다.
- 비밀값(`.env`, API 키, 비밀번호)을 커밋하지 않는다.
