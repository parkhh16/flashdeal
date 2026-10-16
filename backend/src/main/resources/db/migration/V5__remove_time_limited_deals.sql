-- 시간 한정 특가를 없애고 일반 마켓 상품으로 정리한다.
-- 판매 기간(deal_start_at, deal_end_at)과 특가 한정 수량(deal_quantity)을 지운다.
-- 정가(original_price)는 할인 표시용, 1인 구매 제한(per_user_limit)은 상품별 구매 수량 제한으로 남긴다.
-- 이미 적용된 V2는 고치지 않는다 (Flyway는 적용된 파일의 체크섬이 바뀌면 기동을 거부한다)

drop index idx_product_deal_end_at on product;
alter table product drop column deal_start_at;
alter table product drop column deal_end_at;
alter table product drop column deal_quantity;
