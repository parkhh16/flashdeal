-- 서버가 여러 대일 때 스케줄러(주문 만료, 결제 대사)를 한 서버에서만 실행하기 위한 락 테이블 (ShedLock)
-- 각 서버가 같은 이름의 행을 조건부 UPDATE로 차지하고, lock_until이 지나면 다른 서버가 가져갈 수 있다
create table shedlock (
    name       varchar(64)  not null,
    lock_until timestamp(3) not null,
    locked_at  timestamp(3) not null,
    locked_by  varchar(255) not null,
    primary key (name)
);
