-- 상품 카탈로그 확장: 소분류, 특가(원가/기간/한정 수량/1인 제한), 판매량, 상세 설명, 스펙, 이미지
-- 기존 데이터가 있어도 안전하도록 NOT NULL 컬럼은 기본값과 함께 추가한 뒤 채운다.

alter table product add column sub_category varchar(30) not null default 'ACCESSORY_ETC';
update product set sub_category = case category
    when 'KEYBOARD' then 'KEYBOARD_MECHANICAL'
    when 'MOUSE' then 'MOUSE_WIRELESS'
    when 'MONITOR' then 'MONITOR_OFFICE'
    when 'AUDIO' then 'AUDIO_HEADSET'
    else 'ACCESSORY_ETC' end;

alter table product add column original_price bigint;
alter table product add column deal_start_at datetime(6);
alter table product add column deal_end_at datetime(6);
alter table product add column deal_quantity int;
alter table product add column per_user_limit int;
-- 인기순 정렬용. 재고 차감과 같은 UPDATE 문에서 함께 증가시키므로 추가 락 경합이 없다
alter table product add column sold_count int not null default 0;
alter table product add column detail varchar(2000);
alter table product add column specs varchar(4000);
alter table product add column images varchar(2000);

create index idx_product_sub_category on product (sub_category);
create index idx_product_deal_end_at on product (deal_end_at);
