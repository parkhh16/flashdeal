-- AI가 상품 정보를 보고 붙인 검색 태그 (JSON 배열)와 만든 시각.
-- tagged_at이 null인 상품은 백그라운드 작업이 태그를 만든다 (새 상품, 이름·설명이 바뀐 상품, 이 마이그레이션 전부터 있던 상품)
alter table product add column search_tags varchar(500);
alter table product add column tagged_at datetime(6);
