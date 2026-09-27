-- 활동 로그: 요청 단위로 누가/무엇을/결과/지연시간을 남긴다. 관리자 화면과 "내 활동"의 원천 데이터
create table activity_log (
    id          bigint auto_increment primary key,
    user_id     bigint,
    event_type  varchar(30)  not null,
    method      varchar(10)  not null,
    target      varchar(200) not null,
    status_code int          not null,
    latency_ms  int          not null,
    request_id  varchar(64),
    detail      varchar(1000),
    created_at  datetime(6)  not null
);
-- 사용자별 타임라인(WHERE user_id = ? ORDER BY id DESC)과 기간/유형 필터용
create index idx_activity_user_id_id on activity_log (user_id, id);
create index idx_activity_created_at on activity_log (created_at);
create index idx_activity_type_created_at on activity_log (event_type, created_at);

-- 상품 비활성화(판매 중지). 삭제하지 않는 이유: 과거 주문과 로그가 상품을 참조하기 때문
alter table product add column active boolean not null default true;
