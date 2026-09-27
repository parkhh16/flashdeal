-- 초기 스키마 (1차 개발 시점의 JPA 엔티티와 동일)
-- H2(MySQL 모드)와 MySQL 8 양쪽에서 동작하도록 공통 문법만 사용한다.

create table users (
    id         bigint auto_increment primary key,
    email      varchar(100) not null,
    password   varchar(255) not null,
    name       varchar(50)  not null,
    role       varchar(20)  not null,
    created_at datetime(6)  not null,
    updated_at datetime(6)  not null,
    constraint uk_users_email unique (email)
);

create table product (
    id          bigint auto_increment primary key,
    name        varchar(100) not null,
    category    varchar(20)  not null,
    price       bigint       not null,
    stock       int          not null,
    version     bigint       not null,
    description varchar(500),
    created_at  datetime(6)  not null,
    updated_at  datetime(6)  not null
);
create index idx_product_category_price on product (category, price);

create table orders (
    id           bigint auto_increment primary key,
    order_no     varchar(40) not null,
    user_id      bigint      not null,
    status       varchar(20) not null,
    total_amount bigint      not null,
    expires_at   datetime(6) not null,
    version      bigint      not null,
    created_at   datetime(6) not null,
    updated_at   datetime(6) not null,
    constraint uk_orders_order_no unique (order_no)
);
create index idx_orders_user_id_id on orders (user_id, id);
create index idx_orders_status_expires_at on orders (status, expires_at);

create table order_item (
    id           bigint auto_increment primary key,
    order_id     bigint       not null,
    product_id   bigint       not null,
    product_name varchar(100) not null,
    unit_price   bigint       not null,
    quantity     int          not null,
    constraint fk_order_item_order foreign key (order_id) references orders (id)
);
create index idx_order_item_order_id on order_item (order_id);

create table payment (
    id                bigint auto_increment primary key,
    order_id          bigint      not null,
    payment_key       varchar(60) not null,
    amount            bigint      not null,
    status            varchar(20) not null,
    pg_transaction_id varchar(40),
    fail_reason       varchar(100),
    version           bigint      not null,
    created_at        datetime(6) not null,
    updated_at        datetime(6) not null,
    constraint uk_payment_payment_key unique (payment_key)
);
create index idx_payment_order_id on payment (order_id);
create index idx_payment_status_created_at on payment (status, created_at);

create table idempotency_record (
    id              bigint auto_increment primary key,
    user_id         bigint       not null,
    idempotency_key varchar(150) not null,
    request_hash    varchar(64)  not null,
    status          varchar(20)  not null,
    response_status int,
    response_body   varchar(8000),
    created_at      datetime(6)  not null,
    constraint uk_idempotency_user_key unique (user_id, idempotency_key)
);
