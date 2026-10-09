# readOnly 트랜잭션 안의 INSERT가 MySQL에서만 거부된 이유

## 문제

관리자 활동 로그 조회 API가 MySQL에서만 500 에러를 냈습니다. H2에서는 테스트가 전부 통과했습니다.

```
TransientDataAccessResourceException: Connection is read-only.
Queries leading to data modification are not allowed
```

## 원인

활동 로그는 요청마다 메모리 큐에 쌓아 두고 주기적으로 한꺼번에 저장합니다([활동 로그 설계](10-activity-log.md)). 관리자가 로그를 조회할 때 방금 쌓인 로그도 보이도록, 조회 직전에 큐를 비워서(flush) 저장하게 했습니다.

그런데 조회 서비스는 `@Transactional(readOnly = true)`였고, flush의 INSERT가 **그 트랜잭션의 커넥션을 같이 쓰고 있었습니다.**

- **MySQL 드라이버는** readOnly 트랜잭션에서 커넥션을 실제로 읽기 전용으로 만들고, INSERT를 거부합니다.
- **H2는** readOnly 설정을 무시해서 문제가 드러나지 않았습니다.

## 해결

로그 저장은 호출한 쪽의 트랜잭션과 상관없이 항상 **독립된 새 트랜잭션(`REQUIRES_NEW`)** 에서 하도록 바꿨습니다.

`readOnly`가 단순한 성능 힌트가 아니라 실제 제약이 될 수 있다는 걸 확인한 사례입니다. 읽기 전용 복제 DB(replica)로 요청을 라우팅할 때도 이 속성이 기준이 됩니다.

## 같은 테스트를 MySQL에서 돌리게 된 계기

개발은 설치 없이 바로 돌아가는 H2로 했는데, 마지막에 같은 테스트를 MySQL에서 돌리자 3개가 실패했습니다. 원인은 이 readOnly 문제와 [1인 구매 제한의 격리 수준 문제](02-purchase-limit.md), 두 가지였고, 둘 다 H2가 숨기고 있었습니다.

그래서 Gradle 옵션 하나로 **같은 테스트 전체를 MySQL에서 실행**할 수 있게 했습니다.

```bash
cd backend
DB_PASSWORD=<비밀번호> ./gradlew test -Pdb=mysql   # flashdeal_test 스키마를 새로 만들어 사용
```

- 테스트용 스키마(`flashdeal_test`)를 따로 써서 앱 데이터와 섞이지 않습니다.
- 테스트 컨텍스트가 여러 개 떠도 MySQL `max_connections`(151)를 넘지 않도록 커넥션 풀을 작게 잡았습니다.

지금은 테스트 72개가 H2와 MySQL 양쪽에서 모두 통과합니다. 다음 단계는 Testcontainers로 CI에서도 MySQL을 쓰는 것입니다.

이 방식은 그 뒤에도 같은 종류의 문제를 한 번 더 잡아냈습니다. 새로 만든 테스트가 H2에서는 통과하고 MySQL에서만 실패했는데, 시드 데이터의 `admin` 계정에 기대고 있었기 때문입니다. H2는 테스트 컨텍스트마다 DB가 따로라서 항상 시드가 들어가지만, MySQL은 모든 컨텍스트가 `flashdeal_test` 스키마를 공유해서 다른 테스트의 회원이 남아 있으면 시드(회원 테이블이 비어 있을 때만 실행)가 건너뛰어집니다. 테스트가 직접 관리자를 만들도록 고쳤습니다.

## 관련 코드

- [`ActivityRecorder`](../../backend/src/main/java/com/flashdeal/activity/ActivityRecorder.java): `REQUIRES_NEW`로 저장
- [`backend/build.gradle`](../../backend/build.gradle): `-Pdb=mysql` 옵션
- 테스트: `ActivityLogTest`
