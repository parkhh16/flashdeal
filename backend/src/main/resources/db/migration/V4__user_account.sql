-- 회원 계정 기능: 아이디 로그인, 상태(정상/정지/탈퇴), 토큰 무효화, 로그인 잠금
alter table users add column login_id varchar(30);
-- 기존 회원은 이메일 앞부분을 아이디로 쓴다 (예: user1@flashdeal.com → user1)
update users set login_id = left(email, locate('@', email) - 1) where login_id is null;
create unique index uk_users_login_id on users (login_id);

alter table users add column status varchar(20) not null default 'ACTIVE';
-- JWT 즉시 무효화용. 탈퇴/정지/강제 로그아웃 때 1 증가 → 이전 버전 토큰은 거부된다
alter table users add column token_version int not null default 0;
-- 로그인 무차별 대입 방어
alter table users add column failed_login_count int not null default 0;
alter table users add column locked_until datetime(6);
alter table users add column last_login_at datetime(6);
alter table users add column withdrawn_at datetime(6);
