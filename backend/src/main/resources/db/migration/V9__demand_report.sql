-- 못 찾은 검색어를 AI가 묶어 정리한 결과 (판매자 등록 추천). 화면은 가장 최근 것만 보여 준다
create table demand_report (
    id              bigint auto_increment primary key,
    created_at      datetime(6) not null,
    days            int         not null,   -- 최근 며칠의 검색 기록으로 만들었는지
    failed_searches int         not null,   -- 정리에 쓴 못 찾은 검색 수
    content         text        not null    -- JSON 배열: [{need, category, queries, searches, status, products}]
);
